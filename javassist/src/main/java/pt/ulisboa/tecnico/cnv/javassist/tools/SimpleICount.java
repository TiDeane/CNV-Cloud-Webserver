package pt.ulisboa.tecnico.cnv.javassist.tools;

import javassist.CannotCompileException;
import javassist.CtBehavior;

import java.util.List;

public class SimpleICount extends AbstractJavassistTool {

    private static final ThreadLocal<Long> ninsts = ThreadLocal.withInitial(() -> 0L);

    public SimpleICount(List<String> packageNameList, String writeDestination) {
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
            behavior.insertBefore(SimpleICount.class.getName() + ".resetMetrics();");
        }
    }

    @Override
    protected void transform(BasicBlock block) throws CannotCompileException {
        super.transform(block);
        block.behavior.insertAt(block.line, String.format("%s.incBasicBlock(%s, %s);", SimpleICount.class.getName(), block.getPosition(), block.getLength()));
    }

}
