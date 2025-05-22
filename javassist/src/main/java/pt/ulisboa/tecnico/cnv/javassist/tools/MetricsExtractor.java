package pt.ulisboa.tecnico.cnv.javassist.tools;

import javassist.CannotCompileException;
import javassist.CtBehavior;

import java.util.List;

import java.io.FileWriter;
import java.io.BufferedWriter;
import java.io.PrintWriter;
import java.io.IOException;
import java.io.File;

public class MetricsExtractor extends AbstractJavassistTool {

    private static final String LOG_FILE_PATH = "metrics/metrics.txt";

    private static final ThreadLocal<Long> ninsts = ThreadLocal.withInitial(() -> 0L);

    private static final ThreadLocal<String> threadRequestInfo = new ThreadLocal<>();

    public MetricsExtractor(List<String> packageNameList, String writeDestination) {
        super(packageNameList, writeDestination);
    }

    public static void incBasicBlock(int position, int length) {
        ninsts.set(ninsts.get() + length);
    }

    public static void resetMetrics() {
        ninsts.set(0L);
    }

    public static long getInstructions() {
        return ninsts.get();
    }

    // receives info from the handler
    public static void setRequestInfo(String info) {
        threadRequestInfo.set(info);
    }

    public static void clearRequestInfo() {
        threadRequestInfo.remove();
    }

    public static void writeMetrics(String handlerName) {
        long tid = Thread.currentThread().getId();
        String line = String.format(
            "[Thread %d] Handler: %s, Request: %s, Instructions: %d\n",
            tid,
            handlerName,
            threadRequestInfo.get() != null ? threadRequestInfo.get() : "N/A",
            getInstructions()
        );

        try (FileWriter fw = new FileWriter(LOG_FILE_PATH, true);
            BufferedWriter bw = new BufferedWriter(fw);
            PrintWriter out = new PrintWriter(bw)) {
            out.print(line);
        } catch (IOException e) {
            System.err.println("Error writing metrics: " + e.getMessage());
        }
    }

    @Override
    protected void transform(CtBehavior behavior) throws Exception {
        super.transform(behavior);
        if (behavior.getName().equals("handleWorkload")) {
            String handlerName = behavior.getDeclaringClass().getSimpleName();
            behavior.insertBefore(MetricsExtractor.class.getName() + ".resetMetrics();");
            behavior.insertAfter(MetricsExtractor.class.getName() + ".writeMetrics(\"" + handlerName + "\");", true);
        }
    }

    @Override
    protected void transform(BasicBlock block) throws CannotCompileException {
        super.transform(block);
        block.behavior.insertAt(block.line, String.format("%s.incBasicBlock(%s, %s);", MetricsExtractor.class.getName(), block.getPosition(), block.getLength()));
    }

}
