package pt.ulisboa.tecnico.cnv.javassist.tools;

import javassist.CannotCompileException;
import javassist.CtBehavior;

import java.util.List;

public class MetricsExtractor extends AbstractJavassistTool {

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

    public static long getThreadId() {
        return Thread.currentThread().getId();
    }

    @Override
    protected void transform(CtBehavior behavior) throws Exception {
        super.transform(behavior);
        if (behavior.getName().equals("handleWorkload")) {
            String handlerName = behavior.getDeclaringClass().getSimpleName();
            behavior.insertBefore(MetricsExtractor.class.getName() + ".resetMetrics();");
        }
    }

    @Override
    protected void transform(BasicBlock block) throws CannotCompileException {
        super.transform(block);
        block.behavior.insertAt(block.line, String.format("%s.incBasicBlock(%s, %s);", MetricsExtractor.class.getName(), block.getPosition(), block.getLength()));
    }

}
