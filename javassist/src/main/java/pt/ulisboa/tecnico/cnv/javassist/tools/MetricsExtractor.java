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

    private static final ThreadLocal<Long> nblocks = ThreadLocal.withInitial(() -> 0L);
    private static final ThreadLocal<Long> nmethods = ThreadLocal.withInitial(() -> 0L);
    private static final ThreadLocal<Long> ninsts = ThreadLocal.withInitial(() -> 0L);

    private static final ThreadLocal<Long> threadComplexity = ThreadLocal.withInitial(() -> 0L)
    ;
    private static final ThreadLocal<String> threadRequestInfo = new ThreadLocal<>();

    private boolean isHttpHandler(CtBehavior behavior) {
        return behavior.getName().equals("handle");
    }


    public MetricsExtractor(List<String> packageNameList, String writeDestination) {
        super(packageNameList, writeDestination);
    }

    public static void incBasicBlock(int position, int length) {
        nblocks.set(nblocks.get() + 1);
        ninsts.set(ninsts.get() + length);
    }

    public static void incBehavior(String name) {
        nmethods.set(nmethods.get() + 1);
    }

    public static void addComplexity(long amount) {
        threadComplexity.set(threadComplexity.get() + amount);
    }

    public static void resetMetrics() {
        nmethods.set(0L);
        nblocks.set(0L);
        ninsts.set(0L);
        threadComplexity.set(0L);
    }

    public static long getBasicBlocks() {
    return nblocks.get();
}

    public static long getInstructions() {
        return ninsts.get();
    }

    public static long getMethods() {
        return nmethods.get();
    }

    public static long getComplexity() {
        return threadComplexity.get();
    }

    // receives info from the handler
    public static void setRequestInfo(String info) {
        threadRequestInfo.set(info);
    }

    public static void clearRequestInfo() {
        threadRequestInfo.remove();
    }

    public static void reportComplexity(String handlerName) {
        long score = getComplexity();
        long tid = Thread.currentThread().getId();
        String reqInfo = threadRequestInfo.get();
        System.out.printf("[Thread %d] [Handler: %s] URI: %s, Complexity: %d%n",
            tid, handlerName, reqInfo != null ? reqInfo : "N/A", score);
        resetMetrics(); // this might be resetting twice in a row
    }

    // currently unused
    public static void printStatistics() {
        System.out.println(String.format("[%s] Number of executed methods: %s", MetricsExtractor.class.getSimpleName(), nmethods));
        System.out.println(String.format("[%s] Number of executed basic blocks: %s", MetricsExtractor.class.getSimpleName(), nblocks));
        System.out.println(String.format("[%s] Number of executed instructions: %s", MetricsExtractor.class.getSimpleName(), ninsts));
        System.out.println(String.format("[%s] ComplexityScore: %s", MetricsExtractor.class.getSimpleName(), getComplexity()));
    }

    public static void printStatistics2(String handlerName) {
        long tid = Thread.currentThread().getId();
        String line = String.format(
            "[Thread %d] Handler: %s, Request: %s, Methods: %d, Blocks: %d, Instructions: %d, Complexity: %d\n",
            tid,
            handlerName,
            threadRequestInfo.get() != null ? threadRequestInfo.get() : "N/A",
            getMethods(),
            getBasicBlocks(),
            getInstructions(),
            getComplexity()
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
        behavior.insertAfter(String.format("%s.incBehavior(\"%s\");", MetricsExtractor.class.getName(), behavior.getLongName()));

        if (isHttpHandler(behavior)) {
            String handlerName = behavior.getDeclaringClass().getSimpleName();
            behavior.insertBefore(MetricsExtractor.class.getName() + ".resetMetrics();");
            behavior.insertAfter(MetricsExtractor.class.getName() + ".printStatistics2(\"" + handlerName + "\");", true);
        }
    }

    @Override
    protected void transform(BasicBlock block) throws CannotCompileException {
        super.transform(block);
        block.behavior.insertAt(block.line, String.format("%s.incBasicBlock(%s, %s);", MetricsExtractor.class.getName(), block.getPosition(), block.getLength()));

        // Rough complexity: not sure if this is a good metric
        if (block.getLength() > 0) {
            long complexity = Math.max(1, block.getLength() / 5);
            block.behavior.insertAt(block.line, String.format("%s.addComplexity(%dL);", MetricsExtractor.class.getName(), complexity));
        }
    }

}
