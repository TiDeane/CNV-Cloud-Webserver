package pt.ulisboa.tecnico.cnv.javassist.tools;

import javassist.CannotCompileException;
import javassist.CtBehavior;
import javassist.bytecode.CodeAttribute;
import javassist.bytecode.CodeIterator;
import javassist.bytecode.Opcode;

import java.util.List;

public class MetricsExtractor extends AbstractJavassistTool {

    private static final ThreadLocal<Long> ninsts = ThreadLocal.withInitial(() -> 0L);
    private static final ThreadLocal<Long> totalMemoryAccesses = ThreadLocal.withInitial(() -> 0L);

    public MetricsExtractor(List<String> packageNameList, String writeDestination) {
        super(packageNameList, writeDestination);
    }

    public static void incBasicBlock(int position, int length) {
        ninsts.set(ninsts.get() + length);
    }

    public static void resetMetrics() {
        ninsts.set(0L);
        totalMemoryAccesses.set(0L);
    }

    public static long getInstructions() {
        return ninsts.get();
    }

    public static long getThreadId() {
        return Thread.currentThread().getId();
    }

    public static void incTotalMemAccesses(long block_accesses) {
        totalMemoryAccesses.set(totalMemoryAccesses.get() + block_accesses);
    }

    public static long getTotalMemAccesses() {
        return totalMemoryAccesses.get();
    }


    public void countBlockMemAccesses(BasicBlock bb) {
        long memAccesses = 0;
        try {
            CodeAttribute codeAttr = bb.getBehavior().getMethodInfo().getCodeAttribute();

            if (codeAttr == null) return;
            
            CodeIterator iterator = codeAttr.iterator();
            int start = bb.getPosition();
            int end = start + bb.getLength();

            while (iterator.hasNext()) {
                int pos = iterator.next();
                if (pos < start) continue;
                if (pos >= end) break;                
                int opcode = iterator.byteAt(pos);

                switch (opcode) {
                    case Opcode.GETFIELD:
                    case Opcode.PUTFIELD:
                    case Opcode.GETSTATIC:
                    case Opcode.PUTSTATIC:
                    case Opcode.AALOAD:
                    case Opcode.AASTORE:
                    case Opcode.IALOAD:
                    case Opcode.IASTORE:
                    case Opcode.LALOAD:
                    case Opcode.LASTORE:
                    case Opcode.FALOAD:
                    case Opcode.FASTORE:
                    case Opcode.DALOAD:
                    case Opcode.DASTORE:
                    case Opcode.BALOAD:
                    case Opcode.BASTORE:
                    case Opcode.CALOAD:
                    case Opcode.CASTORE:
                    case Opcode.SALOAD:
                    case Opcode.SASTORE:
                        memAccesses++;
                        break;
                }
            }
            bb.setMemAccesses(memAccesses);
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }    
    }


    @Override
    protected void transform(CtBehavior behavior) throws Exception {
        super.transform(behavior);
        if (behavior.getName().equals("handleWorkload")) {
            behavior.insertBefore(MetricsExtractor.class.getName() + ".resetMetrics();");
        }
    }

    @Override
    protected void transform(BasicBlock block) throws CannotCompileException {
        super.transform(block);
        countBlockMemAccesses(block);
        block.behavior.insertAt(block.line, String.format("{ %s.incBasicBlock(%s, %s); %s.incTotalMemAccesses(%dL); }", MetricsExtractor.class.getName(), block.getPosition(), block.getLength(), MetricsExtractor.class.getName(), block.getMemAccesses()));
        // TODO: Later on, try counting memory accesses? Using CodeIterator and opcodes
    }

}
