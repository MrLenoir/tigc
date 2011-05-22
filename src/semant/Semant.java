package semant;

import symbol.*;
import notifier.Notifier;
import absyn.*;
import java.util.*;
import intermediate.*;

public class Semant {
    private Table<Entry> vt;
    private Table<type.Type> tt;
    private Notifier notifier;

    private Stack<FuncEntry> invokingStack;
    private Stack<Label> breakStack;
    private IR ir;

    private Symbol sym(String s) {
        return Symbol.symbol(s);
    }

    private void initTypes() {
        tt.put(sym("int"), new type.Int());
        tt.put(sym("string"), new type.String());
    }

    private void initFunctions() {
        // function print(s : string)
        vt.put(sym("print"), new FuncEntry(
                    new type.Record(sym("s"), new type.String(), null),
                    new type.Void(), null, null, true));

        // function printi(i : int)
        vt.put(sym("printi"), new FuncEntry(
                    new type.Record(sym("i"), new type.Int(), null),
                    new type.Void(), null, null, true));

        // function flush()
        vt.put(sym("flush"), new FuncEntry(
                    null, new type.Void(), null, null, true));

        // function getchar() : string
        vt.put(sym("getchar"), new FuncEntry(
                    null, new type.String(), null, null, true));

        // function ord(s: string) : int
        vt.put(sym("ord"), new FuncEntry(
                    new type.Record(sym("s"), new type.String(), null),
                    new type.Int(), null, null, true));

        // function chr(i: int) : string
        vt.put(sym("chr"), new FuncEntry(
                    new type.Record(sym("i"), new type.Int(), null),
                    new type.String(), null, null, true));

        // function size(s: string) : int
        vt.put(sym("size"), new FuncEntry(
                    new type.Record(sym("s"), new type.String(), null),
                    new type.Int(), null, null, true));

        // function substring(s : string, first: int, n: int) : string
        vt.put(sym("substring"), new FuncEntry(
                    new type.Record(sym("s"), new type.String(),
                        new type.Record(sym("first"), new type.Int(),
                            new type.Record(sym("n"), new type.Int(), null)
                            )
                        ), new type.String(), null, null, true));
        
        // function concat(s1: string, s2: string) : string
        vt.put(sym("concat"), new FuncEntry(
                    new type.Record(sym("s1"), new type.String(),
                        new type.Record(sym("s2"), new type.String(), null)
                        ), new type.String(), null, null, true));

        // function not(i: int): int
        vt.put(sym("not"), new FuncEntry(
                    new type.Record(sym("i"), new type.Int(), null),
                    new type.Int(), null, null, true));

        // function exit(i: int)
        vt.put(sym("exit"), new FuncEntry(
                    new type.Record(sym("i"), new type.Int(), null),
                    new type.Void(), null, null, true));
    }

    public Semant(Notifier notifier) {
        this.notifier = notifier;

        invokingStack = new Stack<FuncEntry>();
        breakStack = new Stack<Label>();
        tt = new Table<type.Type>();
        vt = new Table<Entry>();
        initTypes();
        initFunctions();
    }

    public IR translate(absyn.Expr expr) {
        ir = new IR();
        breakStack.push(null);
        IntermediateCodeList codes = transExpr(expr).codes;
        breakStack.pop();
        ir.codes = codes;
        return ir;
    }

    private void checkType(type.Type left, type.Type right, int pos) {
        if (!right.fits(left))
            notifier.error("Type mismatch, " + left.toString() + " needed, but " + right.toString() + " given", pos);
    }

    private SimpleAccess convertToSimpleAccess(Access access, IntermediateCodeList codes) {
        if (access instanceof MemAccess) {
            TempAccess ta = new TempAccess(Temp.newTemp());
            codes.add(new MoveTAC(access, ta));
            return ta;
        } else
            return (SimpleAccess)access;
    }

    private void calcFullForeigns(ArrayList<FuncEntry> entries) {
        boolean change = false;
        do {
            change = false;
            for (FuncEntry fe: entries) {
                for (FuncEntry.Invoking invoking: fe.invokings) {
                    Entry target = vt.get(invoking.name);
                    if (target instanceof FuncEntry) {
                        HashSet<Symbol> t = new HashSet<Symbol>(((FuncEntry) target).foreigns);
                        t.removeAll(invoking.locals);
                        if (fe.foreigns.addAll(t))
                            change = true;
                    }
                }
            }
        } while (change);
    }

    private TranslateResult transExpr(absyn.Expr expr) {
        if (expr instanceof ArrayExpr)
            return transExpr((ArrayExpr) expr);
        else if (expr instanceof AssignmentExpr)
            return transExpr((AssignmentExpr) expr);
        else if (expr instanceof BreakExpr)
            return transExpr((BreakExpr) expr);
        else if (expr instanceof CallExpr)
            return transExpr((CallExpr) expr);
        else if (expr instanceof ForExpr)
            return transExpr((ForExpr) expr);
        else if (expr instanceof IfExpr)
            return transExpr((IfExpr) expr);
        else if (expr instanceof IntExpr)
            return transExpr((IntExpr) expr);
        else if (expr instanceof LetExpr)
            return transExpr((LetExpr) expr);
        else if (expr instanceof LValueExpr)
            return transExpr((LValueExpr) expr);
        else if (expr instanceof NegationExpr)
            return transExpr((NegationExpr) expr);
        else if (expr instanceof NilExpr)
            return transExpr((NilExpr) expr);
        else if (expr instanceof OpExpr)
            return transExpr((OpExpr) expr);
        else if (expr instanceof RecordExpr)
            return transExpr((RecordExpr) expr);
        else if (expr instanceof SeqExpr)
            return transExpr((SeqExpr) expr);
        else if (expr instanceof StringExpr)
            return transExpr((StringExpr) expr);
        else if (expr instanceof WhileExpr)
            return transExpr((WhileExpr) expr);
        else
            return new TranslateResult(new IntermediateCodeList(), new type.Int());
    }

    private TranslateResult transExpr(ArrayExpr expr) {
        type.Type t = tt.get(expr.type), ta = t.actual();

        if (t == null) {

            notifier.error("Undefined type: " + expr.type.toString() + "; int array assumed.", expr.pos);
            return new TranslateResult(new IntermediateCodeList(), new type.Array(new type.Int()));

        } else if (!(ta instanceof type.Array)) {

            notifier.error(t.toString() + " is not an array type; int array assumed.");
            return new TranslateResult(new IntermediateCodeList(), new type.Array(new type.Int()));

        } else {

            TranslateResult size = transExpr(expr.size);
            checkType(new type.Int(), size.type, expr.size.pos);
            TranslateResult init = transExpr(expr.init);
            checkType(((type.Array)ta).base, init.type, expr.init.pos);

            IntermediateCodeList codes = new IntermediateCodeList();
            codes.addAll(size.codes);
            codes.addAll(init.codes);
            TempAccess tresa = null;
            if (!notifier.hasError()) {
                Temp tsize = Temp.newTemp(), tres = Temp.newTemp();
                tresa = new TempAccess(tres);
                codes.add(new BinOpTAC(BinOpTAC.BinOp.MUL, size.place, ir.wordLength, new TempAccess(tsize)));
                ir.funcTable.put(sym("malloc"));
                codes.add(new CallExternTAC(sym("malloc"), new TempAccess(tsize), null, null, tresa));

                Label l1 = Label.newLabel(), l2 = Label.newLabel();
                Temp ti = Temp.newTemp();
                codes.add(new MoveTAC(new ConstAccess(0), new TempAccess(ti)));
                codes.add(l1, new BranchTAC(BranchTAC.BranchType.GEQ, new TempAccess(ti),
                            new TempAccess(tsize), l2));
                codes.add(new MoveTAC(init.place, new MemAccess(tresa, new TempAccess(ti))));
                codes.add(new BinOpTAC(BinOpTAC.BinOp.ADD, new TempAccess(ti), ir.wordLength, new TempAccess(ti)));
                codes.add(new GotoTAC(l1));
                codes.add(l2);
            }

            return new TranslateResult(codes, t, tresa);
        }
    }

    private TranslateResult transExpr(AssignmentExpr expr) {
        TranslateResult l = transLValue(expr.lvalue, true);
        TranslateResult r = transExpr(expr.e);
        checkType(l.type, r.type, expr.pos);
        
        IntermediateCodeList codes = new IntermediateCodeList();
        if (!notifier.hasError()) {
            codes.addAll(l.codes);
            codes.addAll(r.codes);
            codes.add(new MoveTAC(r.place, (AssignableAccess) l.place));
        }
        return new TranslateResult(codes, new type.Void());
    }

    private TranslateResult transExpr(BreakExpr expr) {
        if (breakStack.peek() == null)
            notifier.error("Invalid break", expr.pos);
        IntermediateCodeList codes = new IntermediateCodeList();
        codes.add(new GotoTAC(breakStack.peek()));
        return new TranslateResult(codes, new type.Void());
    }

    private TranslateResult transExpr(CallExpr expr) {
        Entry e = vt.get(expr.func);
        if (e == null) {
            notifier.error("Undefined function " + expr.func.toString() + "; assumed return VOID", expr.pos);
            return new TranslateResult(null, new type.Void());
        }
        if (e instanceof VarEntry) {
            notifier.error(expr.func.toString() + " is not a function; assumed return VOID", expr.pos);
            return new TranslateResult(null, new type.Void());
        }

        FuncEntry func = (FuncEntry)e;

        type.Record p = func.params;
        ExprList q = expr.args;

        IntermediateCodeList codes = new IntermediateCodeList(),
                             codesParam = new IntermediateCodeList();
        Iterator<FuncEntry.Formal> iter = func.formals.iterator();
        ArrayList<Access> actuals = new ArrayList<Access>();
        while (p != null && !p.isEmpty() && q != null) {
            TranslateResult tq = transExpr(q.expr);
            checkType(p.type, tq.type, q.expr.pos);

            actuals.add(tq.place);
            if (!notifier.hasError())
                codes.addAll(tq.codes);
            if (!func.isExtern && !notifier.hasError())
                codesParam.add(new MoveTAC(tq.place, new TempAccess(iter.next().place)));

            p = p.next;
            q = q.next;
        }

        TempAccess ret = null;
        if (!notifier.hasError()) {
            if (func.isExtern) {
                if (!(func.result.actual() instanceof type.Void))
                    ret = new TempAccess(Temp.newTemp());

                ir.funcTable.put(expr.func);
                switch (actuals.size()) {
                    case 0:
                        codes.add(new CallExternTAC(expr.func, null, null, null, ret));
                        break;

                    case 1:
                        codes.add(new CallExternTAC(expr.func, actuals.get(0), null, null, ret));
                        break;

                    case 2:
                        codes.add(new CallExternTAC(expr.func, actuals.get(0), actuals.get(1), null, ret));
                        break;

                    case 3:
                        codes.add(new CallExternTAC(expr.func, actuals.get(0), actuals.get(1), actuals.get(2), ret));
                        break;

                    default:
                        notifier.error("Too many params in extern call", q.pos);
                        break;
                }
            } else {
                codes.addAll(codesParam);
                ret = new TempAccess(func.tResult);
                codes.add(new CallTAC(func.place));
            }
        }

        if ((p != null && !p.isEmpty()) || q != null)
            notifier.error("Function param number mismatch", expr.pos);

        if (!invokingStack.empty() && invokingStack.peek() != null)
            invokingStack.peek().invokings.add(new FuncEntry.Invoking(expr.func, vt.getLocals()));

        return new TranslateResult(codes, func.result, ret);
    }

    private TranslateResult transExpr(ForExpr expr) {
        TranslateResult br = transExpr(expr.begin),
                        er = transExpr(expr.end);
        checkType(new type.Int(), br.type, expr.begin.pos);
        checkType(new type.Int(), er.type, expr.end.pos);

        Label endLoop = Label.newLabel();
        Temp inductionVar = Temp.newTemp();

        vt.beginScope();
        vt.put(expr.var, new VarEntry(new type.Int(), false, inductionVar));
        breakStack.push(endLoop);
        TranslateResult result = transExpr(expr.body);
        breakStack.pop();
        checkType(new type.Void(), result.type, expr.body.pos);
        vt.endScope();
        
        IntermediateCodeList codes = new IntermediateCodeList();
        if (!notifier.hasError()) {
            codes.addAll(br.codes);
            codes.addAll(er.codes);
            codes.add(new MoveTAC(br.place, new TempAccess(inductionVar)));
            Label beginLoop = Label.newLabel();
            codes.add(beginLoop);
            codes.add(new BranchTAC(BranchTAC.BranchType.GT, new TempAccess(inductionVar), er.place, endLoop));
            codes.addAll(result.codes);
            codes.add(new GotoTAC(beginLoop));
            codes.add(endLoop);
        }

        return new TranslateResult(codes, new type.Void());
    }

    private TranslateResult transExpr(IfExpr expr) {
        TranslateResult cr = transExpr(expr.condition);
        checkType(new type.Int(), cr.type, expr.condition.pos);
        if (expr.elseClause != null) {
            TranslateResult thenr = transExpr(expr.thenClause);
            TranslateResult elser = transExpr(expr.elseClause);
            checkType(thenr.type, elser.type, expr.thenClause.pos);

            IntermediateCodeList codes = new IntermediateCodeList();
            Label elseIf = Label.newLabel(), endIf = Label.newLabel();
            TempAccess place = null;
            if (!(thenr.type.actual() instanceof type.Void))
                place = new TempAccess(Temp.newTemp());
            if (!notifier.hasError()) {
                codes.addAll(cr.codes);
                codes.add(new BranchTAC(BranchTAC.BranchType.EQ, cr.place, new ConstAccess(0), elseIf));

                codes.addAll(thenr.codes);
                if (place != null)
                    codes.add(new MoveTAC(thenr.place, place));
                codes.add(new GotoTAC(endIf));

                codes.add(elseIf);
                codes.addAll(elser.codes);
                if (place != null)
                    codes.add(new MoveTAC(elser.place, place));

                codes.add(endIf);
            }

            return new TranslateResult(codes, thenr.type, place);

        } else {
            TranslateResult thenr = transExpr(expr.thenClause);
            checkType(new type.Void(), thenr.type, expr.thenClause.pos);

            IntermediateCodeList codes = new IntermediateCodeList();
            Label endIf = Label.newLabel();
            if (!notifier.hasError()) {
                codes.addAll(cr.codes);
                codes.add(new BranchTAC(BranchTAC.BranchType.EQ, cr.place, new ConstAccess(0), endIf));
                codes.addAll(thenr.codes);
                codes.add(endIf);
            }

            return new TranslateResult(codes, new type.Void());
        }
    }

    private TranslateResult transExpr(IntExpr expr) {
        return new TranslateResult(new IntermediateCodeList(), new type.Int(), new ConstAccess(expr.value));
    }

    private TranslateResult transExpr(LetExpr expr) {
        vt.beginScope();
        tt.beginScope();
        TranslateResult rd = transDeclList(expr.decls);
        TranslateResult re = transExprList(expr.exprs);
        vt.endScope();
        tt.endScope();

        IntermediateCodeList codes = new IntermediateCodeList();
        if (!notifier.hasError()) {
            codes.addAll(rd.codes);
            codes.addAll(re.codes);
        }
        return new TranslateResult(codes, re.type, re.place);
    }

    private TranslateResult transExpr(LValueExpr expr) {
        return transLValue(expr.lvalue, false);
    }

    private TranslateResult transExpr(NegationExpr expr) {
        TranslateResult te = transExpr(expr.value);
        checkType(new type.Int(), te.type, expr.value.pos);

        IntermediateCodeList codes = new IntermediateCodeList();
        TempAccess place = new TempAccess(Temp.newTemp());
        if (!notifier.hasError()) {
            codes.addAll(te.codes);
            codes.add(new UniOpTAC(UniOpTAC.UniOp.NEG, te.place, place));
        }

        return new TranslateResult(codes, new type.Int(), place);
    }

    private TranslateResult transExpr(NilExpr expr) {
        return new TranslateResult(new IntermediateCodeList(), new type.Nil(), new ConstAccess(0));
    }

    private TranslateResult transExpr(OpExpr expr) {
        TranslateResult lr = transExpr(expr.left),
                        rr = transExpr(expr.right);
        type.Type ltype = lr.type, la = ltype.actual(),
            rtype = rr.type, ra = rtype.actual();

        BinOpTAC.BinOp op = BinOpTAC.BinOp.ADD;
        switch (expr.op) {
            case ADD:
                op = BinOpTAC.BinOp.ADD;
                break;

            case SUB:
                op = BinOpTAC.BinOp.SUB;
                break;

            case MUL:
                op = BinOpTAC.BinOp.MUL;
                break;

            case DIV:
                op = BinOpTAC.BinOp.DIV;
                break;

            case EQ:
                op = BinOpTAC.BinOp.EQ;
                break;

            case NEQ:
                op = BinOpTAC.BinOp.GEQ;
                break;

            case LT:
                op = BinOpTAC.BinOp.LT;
                break;

            case LEQ:
                op = BinOpTAC.BinOp.LEQ;
                break;

            case GT:
                op = BinOpTAC.BinOp.GT;
                break;

            case GEQ:
                op = BinOpTAC.BinOp.GEQ;
                break;
        }


        IntermediateCodeList codes = new IntermediateCodeList();
        TempAccess place = new TempAccess(Temp.newTemp());

        if (la instanceof type.Int || ra instanceof type.Int) {
            checkType(new type.Int(), la, expr.left.pos);
            checkType(new type.Int(), ra, expr.right.pos);

            if (!notifier.hasError()) {
                if (expr.op == OpExpr.Op.AND) {
                    Label falseLabel = Label.newLabel(),
                          endLabel = Label.newLabel();

                    codes.addAll(lr.codes);
                    codes.add(new BranchTAC(BranchTAC.BranchType.EQ, lr.place, new ConstAccess(0), falseLabel));
                    codes.addAll(rr.codes);
                    codes.add(new BranchTAC(BranchTAC.BranchType.EQ, rr.place, new ConstAccess(0), falseLabel));
                    codes.add(new MoveTAC(new ConstAccess(1), place));
                    codes.add(new GotoTAC(endLabel));
                    codes.add(falseLabel, new MoveTAC(new ConstAccess(0), place));
                    codes.add(endLabel);

                } else if (expr.op == OpExpr.Op.OR) {
                    Label trueLabel = Label.newLabel(),
                          endLabel = Label.newLabel();

                    codes.addAll(lr.codes);
                    codes.add(new BranchTAC(BranchTAC.BranchType.NEQ, lr.place, new ConstAccess(0), trueLabel));
                    codes.addAll(rr.codes);
                    codes.add(new BranchTAC(BranchTAC.BranchType.NEQ, rr.place, new ConstAccess(0), trueLabel));
                    codes.add(new MoveTAC(new ConstAccess(0), place));
                    codes.add(new GotoTAC(endLabel));
                    codes.add(trueLabel, new MoveTAC(new ConstAccess(1), place));
                    codes.add(endLabel);

                } else {
                    codes.addAll(lr.codes);
                    codes.addAll(rr.codes);
                    codes.add(new BinOpTAC(op, lr.place, rr.place, place));
                }
            }

        } else if (la instanceof type.String || ra instanceof type.String) {
            if (expr.op == OpExpr.Op.EQ || expr.op == OpExpr.Op.NEQ
                    || expr.op == OpExpr.Op.LT || expr.op == OpExpr.Op.LEQ
                    || expr.op == OpExpr.Op.GT || expr.op == OpExpr.Op.GEQ) {
                checkType(new type.String(), la, expr.left.pos);
                checkType(new type.String(), ra, expr.right.pos);

                if (!notifier.hasError()) {
                    codes.addAll(lr.codes);
                    codes.addAll(rr.codes);
                    TempAccess t = new TempAccess(Temp.newTemp());
                    codes.add(new CallExternTAC(sym("strcmp"), lr.place, rr.place, null, t));
                    switch (expr.op) {
                        case EQ:
                            codes.add(new BinOpTAC(BinOpTAC.BinOp.EQ, t, new ConstAccess(0), place));
                            break;

                        case NEQ:
                            codes.add(new BinOpTAC(BinOpTAC.BinOp.NEQ, t, new ConstAccess(0), place));
                            break;

                        case LT:
                            codes.add(new BinOpTAC(BinOpTAC.BinOp.LT, t, new ConstAccess(0), place));
                            break;

                        case LEQ:
                            codes.add(new BinOpTAC(BinOpTAC.BinOp.LEQ, t, new ConstAccess(0), place));
                            break;

                        case GT:
                            codes.add(new BinOpTAC(BinOpTAC.BinOp.GT, t, new ConstAccess(0), place));
                            break;

                        case GEQ:
                            codes.add(new BinOpTAC(BinOpTAC.BinOp.GEQ, t, new ConstAccess(0), place));
                            break;
                    }
                }

            } else {
                notifier.error("Invalid comparation between strings", expr.pos);
            }
        } else if ((expr.op == OpExpr.Op.EQ || expr.op == OpExpr.Op.NEQ) &&

                (la instanceof type.Array || la instanceof type.Record
                 || ra instanceof type.Array || ra instanceof type.Record)) {
            if (!(ltype.fits(rtype) || rtype.fits(ltype)))
                notifier.error("Invalid comparation between " + ltype.toString()
                        + " and " + rtype.toString(), expr.pos);

            if (!notifier.hasError()) {
                codes.addAll(lr.codes);
                codes.addAll(rr.codes);
                codes.add(new BinOpTAC(op, lr.place, rr.place, place));
            }

        } else
            notifier.error("Invalid comparation between " + ltype.toString()
                    + " and " + rtype.toString(), expr.pos);
        return new TranslateResult(codes, new type.Int(), place);
    }

    private TranslateResult transExpr(RecordExpr expr) {
        type.Type type = tt.get(expr.type);
        if (type == null) {
            notifier.error(expr.type.toString() + " undefined; empty RECORD assumed", expr.pos);
            return new TranslateResult(new IntermediateCodeList(), new type.Record(null, null, null));
        } else if (!(type.actual() instanceof type.Record)) {
            notifier.error(type.toString() + " is not a record; empty RECORD assumed", expr.pos);
            return new TranslateResult(new IntermediateCodeList(), new type.Record(null, null, null));
        } else {
            type.Record p = (type.Record) type.actual();
            FieldList q = expr.fields;

            IntermediateCodeList codes = new IntermediateCodeList();
            TempAccess place = new TempAccess(Temp.newTemp());
            if (!notifier.hasError()) {
                TempAccess tsize = new TempAccess(Temp.newTemp());
                codes.add(new BinOpTAC(BinOpTAC.BinOp.MUL, new ConstAccess(p.length()), ir.wordLength, tsize));
                ir.funcTable.put(sym("malloc"));
                codes.add(new CallExternTAC(sym("malloc"), tsize, null, null, place));
            }

            int offset = 0;
            while ((p != null && !p.isEmpty()) && q != null) {
                if (p.field != q.name)
                    notifier.error("Field name mismatch: " + p.field.toString() + " expected but"
                           + q.name.toString() + " found", q.pos);
                TranslateResult qr = transExpr(q.value);
                checkType(p.type, qr.type, q.value.pos);

                if (!notifier.hasError()) {
                    codes.addAll(qr.codes);
                    codes.add(new MoveTAC(qr.place, new MemAccess(place, new ConstAccess(offset))));
                }

                p = p.next;
                q = q.next;
                offset += 4;
            }

            if ((p != null && !p.isEmpty()) || q != null)
                notifier.error("Field number mismatch", expr.fields.pos);

            return new TranslateResult(codes, type, place);
        }
    }

    private TranslateResult transExpr(SeqExpr expr) {
        return transExprList(expr.exprList);
    }

    private TranslateResult transExpr(StringExpr expr) {
        return new TranslateResult(new IntermediateCodeList(),
                new type.String(), ir.stringTable.get(expr.value));
    }

    private TranslateResult transExpr(WhileExpr expr) {
        Label beginWhile = Label.newLabel(),
              endWhile = Label.newLabel();

        TranslateResult cr = transExpr(expr.condition);
        breakStack.push(endWhile);
        TranslateResult br = transExpr(expr.body);
        breakStack.pop();
        checkType(new type.Int(), cr.type, expr.condition.pos);
        checkType(new type.Void(), br.type, expr.body.pos);

        IntermediateCodeList codes = new IntermediateCodeList();
        if (!notifier.hasError()) {
            codes.add(beginWhile);
            codes.addAll(cr.codes);
            codes.add(new BranchTAC(BranchTAC.BranchType.EQ,
                        cr.place, new ConstAccess(0), endWhile));
            codes.addAll(br.codes);
            codes.add(new GotoTAC(beginWhile));
            codes.add(endWhile);
        }

        return new TranslateResult(codes, new type.Void());
    }

    private TranslateResult transExprList(ExprList expr) {
        type.Type retType = new type.Void();
        IntermediateCodeList codes = new IntermediateCodeList();
        Access place = null;
        while (expr != null) {
            TranslateResult r = transExpr(expr.expr);
            retType = r.type;
            if (!notifier.hasError()) {
                codes.addAll(r.codes);
                place = r.place;
            }
            expr = expr.next;
        }
        return new TranslateResult(codes, retType, place);
    }

    private TranslateResult transDeclList(DeclList expr) {
        if (expr == null)
            return new TranslateResult(new IntermediateCodeList(), null);

        IntermediateCodeList codes = new IntermediateCodeList();
        if (expr.decl instanceof VarDecl) {

            VarDecl vd = (VarDecl) expr.decl;
            if (vd.type != null) {
                type.Type type = tt.get(vd.type);
                if (type == null)
                    notifier.error(vd.type.toString() + " undefined");
                else {
                    Temp t = Temp.newTemp();
                    TempAccess ta = new TempAccess(t);

                    vt.put(vd.id, new VarEntry(type, t));
                    TranslateResult ir = transExpr(vd.value);
                    checkType(type, ir.type, vd.value.pos);
                    
                    if (!notifier.hasError()) {
                        codes.addAll(ir.codes);
                        codes.add(new MoveTAC(ir.place, ta));
                    }
                }
            } else {
                Temp t = Temp.newTemp();
                TempAccess ta = new TempAccess(t);

                TranslateResult ir = transExpr(vd.value);
                type.Type type = ir.type;
                type.Type a = type.actual();
                if (a instanceof type.Nil || a instanceof type.Void) {
                    notifier.error("Invalid initialize type: " + type.toString()
                            + "; INT assumed");
                    type = new type.Int();
                }
                vt.put(vd.id, new VarEntry(type, t));

                if (!notifier.hasError()) {
                    codes.addAll(ir.codes);
                    codes.add(new MoveTAC(ir.place, ta));
                }
            }

            codes.addAll(transDeclList(expr.next).codes);
            return new TranslateResult(codes, null);

        } else if (expr.decl instanceof TypeDecl) {

            DeclList p = expr;
            HashSet<Symbol> set = new HashSet<Symbol>();
            for (p = expr; p != null && p.decl instanceof TypeDecl; p = p.next) {
                TypeDecl td = (TypeDecl) p.decl;

                if (set.add(td.name))
                    tt.put(td.name, new type.Name(td.name));
                else
                    notifier.error(td.name.toString() + " already defined in the same block", td.pos);
            }
            for (p = expr; p != null && p.decl instanceof TypeDecl; p = p.next) {
                TypeDecl td = (TypeDecl) p.decl;
                ((type.Name) tt.get(td.name)).bind(transType(td.type));
            }
            for (p = expr; p != null && p.decl instanceof TypeDecl; p = p.next) {
                TypeDecl td = (TypeDecl) p.decl;
                if (((type.Name) tt.get(td.name)).isLoop()) {
                    notifier.error("Type declaration loop found on " + td.name.toString()
                            + "; INT assumed", td.pos);
                    ((type.Name) tt.get(td.name)).bind(new type.Int());
                }
            }

            return transDeclList(p);

        } else /*if (expr.decl instanceof FuncDecl)*/ {

            DeclList p = expr;
            HashSet<Symbol> set = new HashSet<Symbol>();
            for (p = expr; p != null && p.decl instanceof FuncDecl; p = p.next) {
                FuncDecl fd = (FuncDecl) p.decl;

                if (set.add(fd.name)) {
                    type.Type result = new type.Void();
                    if (fd.type != null)
                        result = tt.get(fd.type);
                    if (result == null) {
                        notifier.error(fd.type.toString() + " undefined; assumed INT", fd.pos);
                        result = new type.Int();
                    }
                    Temp tResult = null;
                    if (!(result.actual() instanceof type.Void))
                        tResult = Temp.newTemp();
                    
                    type.Record pp = transTypeFields(fd.params);
                    FuncEntry entry = new FuncEntry(pp, result, tResult, Label.newLabel(), false);
                    entry.formals = new LinkedList<FuncEntry.Formal>();
                    while (pp != null && !pp.isEmpty()) {
                        entry.formals.add(new FuncEntry.Formal(pp.field, pp.type, Temp.newTemp()));
                        pp = pp.next;
                    }
                    vt.put(fd.name, entry);
                }
                else
                    notifier.error(fd.name.toString() + " already defined in the same block", fd.pos);
            }
            ArrayList<FuncEntry> funcEntries = new ArrayList<FuncEntry>();
            for (p = expr; p != null && p.decl instanceof FuncDecl; p = p.next) {
                FuncDecl fd = (FuncDecl) p.decl;
                
                vt.beginScope(true);

                FuncEntry fe = (FuncEntry) vt.get(fd.name);
                funcEntries.add(fe);
                for (FuncEntry.Formal f: fe.formals)
                    vt.put(f.name, new VarEntry(f.type, f.place));

                invokingStack.push(fe);
                breakStack.push(null);
                TranslateResult te = transExpr(fd.body);
                breakStack.pop();
                invokingStack.pop();

                checkType(fe.result, te.type, fd.body.pos);

                vt.endScope();

                if (!notifier.hasError()) {
                    codes.add(fe.place);
                    codes.addAll(te.codes);
                    if (!(fe.result.actual() instanceof type.Void))
                        codes.add(new MoveTAC(te.place, new TempAccess(fe.tResult)));
                    codes.add(new ReturnTAC());
                }
            }
            calcFullForeigns(funcEntries);

            Label skip = Label.newLabel();
            codes.addFirst(new GotoTAC(skip));
            codes.add(skip);
            codes.addAll(transDeclList(p).codes);
            return new TranslateResult(codes, null);

        }
    }
    
    private type.Record transTypeFields(TypeFields fields) {
        type.Record ret = null;
        if (fields == null)
            ret = new type.EmptyRecord();
        else {
            type.Record last = null;

            while (fields != null) {
                type.Type fieldType = tt.get(fields.head.type);
                if (fieldType == null) {
                    notifier.error("Undefined type " + fields.head.type.toString()
                            + "; INT assumed", fields.head.pos);
                    fieldType = new type.Int();
                }
                type.Record temp = new type.Record(fields.head.name, fieldType, null);
                if (last != null) {
                    last.next = temp;
                    last = last.next;
                } else {
                    ret = temp;
                    last = temp;
                }
                fields = fields.next;
            }
        }
        return ret;
    }

    private type.Type transType(Type type) {
        if (type instanceof NameType) {
            NameType nt = (NameType) type;
            type.Type t = tt.get(nt.name);
            if (t == null) {
                notifier.error("Undefined type " + nt.name.toString()
                        + "; INT assumed", nt.pos);
                t = new type.Int();
            }
            return t;
        } else if (type instanceof ArrayType) {
            ArrayType at = (ArrayType) type;
            type.Type t = tt.get(at.base);
            if (t == null) {
                notifier.error("Undefined type " + at.base.toString()
                        + "; INT assumed", at.pos);
                t = new type.Int();
            }
            return new type.Array(t);
        } else /*if (type instanceof RecordType)*/ {
            RecordType rt = (RecordType) type;
            TypeFields fields = rt.fields;
            return transTypeFields(fields);
       }
    }

    private TranslateResult transLValue(LValue lvalue, boolean assignment) {
        if (lvalue instanceof VarLValue) {

            VarLValue vl = (VarLValue) lvalue;
            Entry entry = vt.get(vl.name);
            type.Type type = null;
            Access place = null;
            if (entry == null) {
                notifier.error("Undefined variable " + vl.name.toString()
                        + "; type INT assumed", vl.pos);
                type = new type.Int();
            } else if (entry instanceof FuncEntry) {
                notifier.error(vl.name.toString() + " is a function, not a variable; type INT assumed", vl.pos);
                type = new type.Int();
            } else {
                type = ((VarEntry) entry).type;
                place = new TempAccess(((VarEntry) entry).place);
                if (assignment && !((VarEntry) entry).assignable)
                    notifier.error(vl.name.toString() + " cannot be assigned here", vl.pos);
                if (vt.isForeign(vl.name) && !invokingStack.empty() && invokingStack.peek() != null)
                    invokingStack.peek().foreigns.add(vl.name);
            }
            return new TranslateResult(new IntermediateCodeList(), type, place);

        } else if (lvalue instanceof FieldLValue) {

            FieldLValue fl = (FieldLValue) lvalue;
            TranslateResult tr = transLValue(fl.lvalue, assignment);
            type.Type type = tr.type, ta = type.actual(), ret = null;

            Access place = null;
            IntermediateCodeList codes = new IntermediateCodeList();
            if (!notifier.hasError())
                codes.addAll(tr.codes);

            if (ta instanceof type.Record) {
                type.Record temp = (type.Record) ta;
                ret = temp.findField(fl.id);
                if (ret == null) {
                    notifier.error(type.toString() + " do not have field " + fl.id
                            + "; type INT assumed", fl.pos);
                    ret = new type.Int();
                } else {

                    if (!notifier.hasError()) {
                        int offset = temp.fieldIndex(fl.id);
                        TempAccess to = new TempAccess(Temp.newTemp());
                        codes.add(new BinOpTAC(BinOpTAC.BinOp.MUL, new ConstAccess(offset), ir.wordLength, to));
                        SimpleAccess sa = convertToSimpleAccess(tr.place, codes);
                        place = new MemAccess(sa, to);
                    }

                }
            } else {
                notifier.error(type.toString() + " is not a RECORD; type INT assumed", fl.pos);
                ret = new type.Int();
            }
            return new TranslateResult(codes, ret, place);

        } else /*if (lvalue instanceof SubscriptLValue)*/ {
            
            SubscriptLValue sl = (SubscriptLValue) lvalue;
            TranslateResult tr = transLValue(sl.lvalue, assignment);
            type.Type type = tr.type, ta = type.actual(), ret = null;

            Access place = null;
            IntermediateCodeList codes = new IntermediateCodeList();

            if (!(ta instanceof type.Array)) {
                notifier.error(type.toString() + " is not an ARRAY", sl.pos);
                ret = new type.Int();
            } else {
                ret = ((type.Array) ta).base;
            }
            TranslateResult tr2 = transExpr(sl.expr);
            checkType(new type.Int(), tr2.type, sl.expr.pos);

            if (!notifier.hasError()) {
                codes.addAll(tr.codes);
                codes.addAll(tr2.codes);
                SimpleAccess sa = convertToSimpleAccess(tr.place, codes);
                TempAccess to = new TempAccess(Temp.newTemp());
                codes.add(new BinOpTAC(BinOpTAC.BinOp.MUL, tr2.place, ir.wordLength, to));
                place = new MemAccess(sa, to);
            }

            return new TranslateResult(codes, ret, place);

        } 
    }
}

