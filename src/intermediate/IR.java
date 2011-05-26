package intermediate;

import arch.*;
import frame.*;
import java.util.*;

public class IR {
    public StringTable stringTable = new StringTable();
    public ExternFunctionTable funcTable = new ExternFunctionTable();
    public UnknownConstAccess wordLength = new UnknownConstAccess("WORD_LENGTH");
    public IntermediateCodeList codes = new IntermediateCodeList();
    public Frame globalFrame = null;
    public ArrayList funcFrames = new ArrayList<Frame>();

    public IR(Frame frame) {
        globalFrame = frame;
    }
}

