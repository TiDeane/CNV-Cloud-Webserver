package pt.ulisboa.tecnico.cnv.javassist.tools;

import java.util.List;

import javassist.CannotCompileException;
import javassist.CtBehavior;

public class MetricsExtractor extends AbstractJavassistTool {

    /**
     * Number of executed basic blocks.
     */
    private static long nblocks = 0;

    /**
     * Number of executed methods.
     */
    private static long nmethods = 0;

    /**
     * Number of executed instructions.
     */
    private static long ninsts = 0;

    private static final ThreadLocal<Long> threadComplexity = ThreadLocal.withInitial(() -> 0L);
    private static final ThreadLocal<String> threadRequestInfo = new ThreadLocal<>();

    private boolean isHttpHandler(CtBehavior behavior) {
        return behavior.getName().equals("handle");
    }


    public MetricsExtractor(List<String> packageNameList, String writeDestination) {
        super(packageNameList, writeDestination);
    }

    public static void incBasicBlock(int position, int length) {
        nblocks++;
        ninsts += length;
    }

    public static void incBehavior(String name) {
        nmethods++;
    }

    public static void addComplexity(long amount) {
        threadComplexity.set(threadComplexity.get() + amount);
    }

    public static void resetComplexity() {
        threadComplexity.set(0L);
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
        resetComplexity();
    }

    // currently unused
    public static void printStatistics() {
        System.out.println(String.format("[%s] Number of executed methods: %s", MetricsExtractor.class.getSimpleName(), nmethods));
        System.out.println(String.format("[%s] Number of executed basic blocks: %s", MetricsExtractor.class.getSimpleName(), nblocks));
        System.out.println(String.format("[%s] Number of executed instructions: %s", MetricsExtractor.class.getSimpleName(), ninsts));
        System.out.println(String.format("[%s] ComplexityScore: %s", MetricsExtractor.class.getSimpleName(), getComplexity()));
    }

    @Override
    protected void transform(CtBehavior behavior) throws Exception {
        super.transform(behavior);

        if (isHttpHandler(behavior)) {
            String handlerName = behavior.getDeclaringClass().getSimpleName();
            behavior.insertBefore(MetricsExtractor.class.getName() + ".resetComplexity();");
            behavior.insertAfter(MetricsExtractor.class.getName() + ".reportComplexity(\"" + handlerName + "\");", true);
        }
    }

    @Override
    protected void transform(BasicBlock block) throws CannotCompileException {
        super.transform(block);

        // Rough complexity: each block after the first adds branching paths
        if (block.getLength() > 0) {
            long complexity = Math.max(1, block.getLength() / 5);
            block.behavior.insertAt(block.line, String.format("%s.addComplexity(%dL);", MetricsExtractor.class.getName(), complexity));
        }

        // remove this? it's currently unused
        //block.behavior.insertAt(block.line, String.format("%s.incBasicBlock(%s, %s);", MetricsExtractor.class.getName(), block.getPosition(), block.getLength()));
    }

}
