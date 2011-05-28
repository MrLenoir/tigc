package mips32;

import intermediate.*;
import util.*;
import java.util.*;
import frame.Frame;
import notifier.Notifier;
import regalloc.*;
import arch.Const;

public class CodeGen {
    static class MipsMemStyle {
        Temp base;
        Const offset;

        MipsMemStyle(Temp base, Const offset) {
            this.base = base;
            this.offset = offset;
        }
    }

    Notifier notifier;

    HashMap<Label, ThreeAddressCode> labelMap;
    IR ir;
    Temp zero, fp, sp, ra;
    int wordLength = 4;

    static class SavePlace {
        LabeledInstruction save, restore;
        Frame frame;

        public SavePlace(Frame frame, LabeledInstruction save, LabeledInstruction restore) {
            this.save = save;
            this.restore = restore;
            this.frame = frame;
        }
    }

    ArrayList<SavePlace> callSaves;

    public CodeGen(Notifier notifier, IR ir) {
        this.notifier = notifier;
        this.ir = ir;
        ir.wordLength.bind(wordLength);
        labelMap = new HashMap<Label, ThreeAddressCode>();
        
        HashSet<Label> labels = new HashSet<Label>();
        for (IntermediateCode ic: ir.codes) {
            if (ic.label != null)
                labels.add(ic.label);
            if (ic.tac != null) {
                for (Label l: labels)
                    labelMap.put(l, ic.tac);
                labels.clear();
            }
        }
        zero = ir.globalFrame.addLocal();
        fp = ir.globalFrame.addLocal();
        sp = ir.globalFrame.addLocal();
        ra = ir.globalFrame.addLocal();
        callSaves = new ArrayList<SavePlace>();
    }

    Const processConstAccess(ConstAccess ca) {
        if (ca instanceof UnknownConstAccess) {
            if (ca == ir.wordLength)
                return new Const(wordLength);
            else
                return new Const(((UnknownConstAccess) ca).name);
        } else
            return new Const(ca.value);
    }

    Const processNegConstAccess(ConstAccess ca) {
        if (ca instanceof UnknownConstAccess) {
            if (ca == ir.wordLength)
                return new Const(-wordLength);
            else
                throw new Error("Unexpected UnknownConstAccess");
        } else
            return new Const(-ca.value);
    }

    MipsMemStyle processMemAccess(InstructionList list, Frame frame, MemAccess ma) {
        if (ma.base instanceof Temp && ma.offset instanceof Temp) {
            Temp t = frame.addLocal();
            list.add(Instruction.ADD(frame, t, (Temp) ma.base, (Temp) ma.offset));
            return new MipsMemStyle(t, new Const(0));
        } else if (ma.base instanceof Temp && ma.offset instanceof ConstAccess) {
            return new MipsMemStyle((Temp) ma.base, processConstAccess((ConstAccess) ma.offset));
        } else if (ma.base instanceof ConstAccess && ma.offset instanceof Temp) {
            return new MipsMemStyle((Temp) ma.offset, processConstAccess((ConstAccess) ma.base));
        } else {
            if (ma.base instanceof UnknownConstAccess || ma.offset instanceof UnknownConstAccess)
                throw new Error("Unexpected UnknownConstAccess");
            return new MipsMemStyle(zero, new Const(((ConstAccess) ma.base).value + ((ConstAccess) ma.offset).value));
        }
    }

    public InstructionList generate() {
        InstructionList list = new InstructionList();
        for (IntermediateCode ic: ir.codes) {
            if (ic.label != null)
                list.add(ic.label);
            if (ic.tac != null) {
                generate(list, ic.tac);
            }
        }
        TempMap map = new TempMap();
        for (LabeledInstruction ins: list) {
            notifier.message(ins.toString(map));
        }
        return list;
    }

    boolean isStringConstant(Access access) {
        if (access instanceof UnknownConstAccess && access != ir.wordLength)
            return true;
        else
            return false;
    }

    boolean isZeroConstant(ConstAccess access) {
        if (!(access instanceof UnknownConstAccess) && access.value == 0)
            return true;
        else
            return false;
    }

    Temp toTemp(InstructionList list, Frame frame, Access access) {
        if (access instanceof Temp)
            return (Temp) access;
        else if (access instanceof ConstAccess && isZeroConstant((ConstAccess) access))
            return zero;
        else {
            Temp t = frame.addLocal();
            generate(list, new MoveTAC(frame, access, t));
            return t;
        }
    }

    public void generate(InstructionList list, ThreeAddressCode tac) {
        if (tac instanceof MoveTAC)
            generate(list, (MoveTAC) tac);
        else if (tac instanceof OpTAC)
            generate(list, (OpTAC) tac);
        else if (tac instanceof CallTAC)
            generate(list, (CallTAC) tac);
        else if (tac instanceof CallExternTAC)
            generate(list, (CallExternTAC) tac);
        else if (tac instanceof ReturnTAC)
            generate(list, (ReturnTAC) tac);
        else if (tac instanceof GotoTAC)
            generate(list, (GotoTAC) tac);
        else /*if (tac instanceof BranchTAC)*/
            generate(list, (BranchTAC) tac);
    }

    public void generate(InstructionList list, MoveTAC tac) {
        if (tac.dst instanceof Temp) {
            if (tac.op1 instanceof Temp) {
                list.add(Instruction.MOVE(tac.frame, (Temp) tac.dst, (Temp) tac.op1));
            } else if (tac.op1 instanceof ConstAccess) {
                if (isStringConstant(tac.op1))
                    list.add(Instruction.LA(tac.frame, (Temp) tac.dst, ((UnknownConstAccess) tac.op1).name));
                else
                    list.add(Instruction.LI(tac.frame, (Temp) tac.dst, processConstAccess((ConstAccess) tac.op1)));
            } else if (tac.op1 instanceof MemAccess) {
                MipsMemStyle m = processMemAccess(list, tac.frame, (MemAccess) tac.op1);
                list.add(Instruction.LW(tac.frame, (Temp) tac.dst, m.base, m.offset));
            }
        } else if (tac.dst instanceof MemAccess) {
            MipsMemStyle m = processMemAccess(list, tac.frame, (MemAccess) tac.dst);
            if (tac.op1 instanceof Temp) {
                list.add(Instruction.SW(tac.frame, (Temp) tac.op1, m.base, m.offset));
            } else if (tac.op1 instanceof ConstAccess) {
                if (isZeroConstant((ConstAccess) tac.op1))
                    list.add(Instruction.SW(tac.frame, zero, m.base, m.offset));
                else {
                    Temp t = tac.frame.addLocal();
                    generate(list, new MoveTAC(tac.frame, tac.op1, t));
                    list.add(Instruction.SW(tac.frame, t, m.base, m.offset));
                }
            } else if (tac.op1 instanceof MemAccess) {
                Temp t = tac.frame.addLocal();
                generate(list, new MoveTAC(tac.frame, tac.op1, t));
                list.add(Instruction.SW(tac.frame, t, m.base, m.offset));
            }
        }
    }

    public void generate(InstructionList list, OpTAC tac) {
        if (tac instanceof BinOpTAC) {
            if (tac.dst instanceof Temp) {
                boolean imm = false;
                if (((BinOpTAC) tac).op == BinOpTAC.BinOp.ADD
                        || ((BinOpTAC) tac).op == BinOpTAC.BinOp.LT
                        || ((BinOpTAC) tac).op == BinOpTAC.BinOp.SUB) {
                    if (tac.op1 instanceof Temp && tac.op2 instanceof ConstAccess) {
                        imm = true;
                        if (((BinOpTAC) tac).op == BinOpTAC.BinOp.ADD)
                            list.add(Instruction.ADDI(tac.frame, (Temp) tac.dst,
                                        (Temp) tac.op1, processConstAccess((ConstAccess) tac.op2)));
                        else if (((BinOpTAC) tac).op == BinOpTAC.BinOp.LT)
                            list.add(Instruction.SLTI(tac.frame, (Temp) tac.dst,
                                        (Temp) tac.op1, processConstAccess((ConstAccess) tac.op2)));
                        else
                            list.add(Instruction.ADDI(tac.frame, (Temp) tac.dst,
                                        (Temp) tac.op1, processNegConstAccess((ConstAccess) tac.op2)));
                    } else if (tac.op1 instanceof ConstAccess && tac.op2 instanceof Temp) {
                        imm = true;
                        generate(list, new BinOpTAC(tac.frame, BinOpTAC.BinOp.ADD,
                                    tac.op2, tac.op1, (Temp) tac.dst));
                    }
                }
                if (imm == false) {
                    Temp op1 = toTemp(list, tac.frame, tac.op1), op2 = toTemp(list, tac.frame, tac.op2);
                    switch (((BinOpTAC) tac).op) {
                        case ADD:
                            list.add(Instruction.ADD(tac.frame, (Temp) tac.dst, op1, op2));
                            break;

                        case SUB:
                            list.add(Instruction.SUB(tac.frame, (Temp) tac.dst, op1, op2));
                            break;

                        case MUL:
                            list.add(Instruction.MUL(tac.frame, (Temp) tac.dst, op1, op2));
                            break;

                        case DIV:
                            list.add(Instruction.DIV(tac.frame, (Temp) tac.dst, op1, op2));
                            break;

                        case EQ:
                            list.add(Instruction.SEQ(tac.frame, (Temp) tac.dst, op1, op2));
                            break;

                        case NEQ:
                            list.add(Instruction.SNE(tac.frame, (Temp) tac.dst, op1, op2));
                            break;

                        case GT:
                            list.add(Instruction.SGT(tac.frame, (Temp) tac.dst, op1, op2));
                            break;

                        case GEQ:
                            list.add(Instruction.SGE(tac.frame, (Temp) tac.dst, op1, op2));
                            break;

                        case LT:
                            list.add(Instruction.SLT(tac.frame, (Temp) tac.dst, op1, op2));
                            break;

                        case LEQ:
                            list.add(Instruction.SLE(tac.frame, (Temp) tac.dst, op1, op2));
                            break;
                    }
                }
            } else if (tac.dst instanceof MemAccess) {
                Temp t = tac.frame.addLocal();
                generate(list, new BinOpTAC(tac.frame, ((BinOpTAC) tac).op, tac.op1, tac.op2, t));
                MipsMemStyle m = processMemAccess(list, tac.frame, (MemAccess) tac.dst);
                list.add(Instruction.SW(tac.frame, t, m.base, m.offset));
            }
        } else if (tac instanceof UniOpTAC) {
            if (((UniOpTAC) tac).op == UniOpTAC.UniOp.NEG) {
                if (tac.dst instanceof Temp) {
                    if (tac.op1 instanceof Temp) {
                        list.add(Instruction.NEG(tac.frame, (Temp) tac.dst, (Temp) tac.op1));
                    } else if (tac.op1 instanceof ConstAccess) {
                        if (isZeroConstant((ConstAccess) tac.op1))
                            list.add(Instruction.NEG(tac.frame, (Temp) tac.dst, zero));
                        else {
                            list.add(Instruction.LI(tac.frame, (Temp) tac.dst, new Const(-((ConstAccess) tac.op1).value)));
                        }
                    } else if (tac.op1 instanceof MemAccess) {
                        MipsMemStyle m = processMemAccess(list, tac.frame, (MemAccess) tac.op1);
                        Temp t = tac.frame.addLocal();
                        list.add(Instruction.LW(tac.frame, t, m.base, m.offset));
                        list.add(Instruction.NEG(tac.frame, (Temp) tac.dst, t));
                    }
                } else if (tac.dst instanceof MemAccess) {
                    Temp t = tac.frame.addLocal();
                    generate(list, new UniOpTAC(tac.frame, UniOpTAC.UniOp.NEG, tac.op1, t));
                    MipsMemStyle m = processMemAccess(list, tac.frame, (MemAccess) tac.dst);
                    list.add(Instruction.SW(tac.frame, t, m.base, m.offset));
                }
            }
        }
    }

    void addSpecialInstruction(InstructionList list, Frame frame, Temp dst, Access src) {
        if (src instanceof Temp) {
            Instruction ins = Instruction.MOVE(frame, dst, (Temp) src);
            ins.special = true;
            list.add(ins);
        } else if (src instanceof ConstAccess) {
            if (isStringConstant(src)) {
                Instruction ins = Instruction.LA(frame, dst, ((UnknownConstAccess) src).name);
                ins.special = true;
                list.add(ins);
            } else {
                Instruction ins = Instruction.LI(frame, dst, new Const(((ConstAccess) src).value));
                ins.special = true;
                list.add(ins);
            }
        } else if (src instanceof MemAccess) {
            MipsMemStyle m = processMemAccess(list, frame, (MemAccess) src);
            Instruction ins = Instruction.LW(frame, dst, m.base, m.offset);
            ins.special = true;
            list.add(ins);
        }
    }

    public void generate(InstructionList list, CallTAC tac) {
        Frame callee = labelMap.get(tac.place).frame;
        callee.updateFrameSize(wordLength);
        
        Label retLabel = Label.newLabel();
        callee.returns.add(retLabel);

        boolean needsave = false;
        LabeledInstruction save = null, restore = null;
        if (ir.callingGraph.isLoopEdge(tac.frame, callee)) {
            needsave = true;
            save = list.addPlaceHolder();
        }

        list.add(Instruction.ADDI(tac.frame, sp, sp, new Const(-wordLength)));

        Iterator<Access> iter = tac.params.iterator();
        for (Temp t: tac.frame.params)
            addSpecialInstruction(list, tac.frame, t, iter.next());

        list.add(Instruction.SW(tac.frame, fp, sp, new Const(0)));
        list.add(Instruction.SW(tac.frame, ra, sp, new Const(-wordLength)));
        list.add(Instruction.SW(tac.frame, callee.display, sp, new Const(-2 * wordLength)));
        list.add(Instruction.MOVE(tac.frame, callee.display, sp));
        list.add(Instruction.MOVE(tac.frame, fp, sp));
        list.add(Instruction.ADDI(callee, sp, sp, callee.minusFrameSize));

        list.add(Instruction.JAL(callee, tac.place));

        list.add(retLabel);
        list.add(Instruction.ADDI(callee, sp, sp, callee.frameSize));
        list.add(Instruction.LW(callee, fp, sp, new Const(0)));
        list.add(Instruction.LW(tac.frame, callee.display, sp, new Const(-2 * wordLength)));
        list.add(Instruction.LW(tac.frame, ra, sp, new Const(-wordLength)));

        if (callee.returnValue != null)
            addSpecialInstruction(list, tac.frame, tac.actualReturn, callee.returnValue);

        list.add(Instruction.ADDI(tac.frame, sp, sp, new Const(wordLength)));

        if (needsave) {
            restore = list.addPlaceHolder();
            callSaves.add(new SavePlace(tac.frame, save, restore));
        }
    }

    public void generate(InstructionList list, CallExternTAC tac) {
    }

    public void generate(InstructionList list, ReturnTAC tac) {
    }

    public void generate(InstructionList list, GotoTAC tac) {
    }

    public void generate(InstructionList list, BranchTAC tac) {
    }

}
