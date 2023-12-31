import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Interpreter {
    private static class LineManager{
        private LinkedList<String> lines;
        
        public LineManager(List<String> lines){
            globalVariables.put("NR", new InterpreterDataType("0"));
            globalVariables.put("FNR", new InterpreterDataType("0"));
            if(lines.isEmpty())
                this.lines = new LinkedList<>(List.of(""));
            else
                this.lines = new LinkedList<>(lines);
        }
        
        public void switchFile(List<String> newLines){
            this.lines = new LinkedList<>(newLines);
            globalVariables.put("FNR", new InterpreterDataType("0")); // Reset FNR
            if(!handleNextLine())
                splitAndAssign("");
        }
        
        // This splits & assigns the next line, progresses the line manager
        public boolean handleNextLine(){
            if(lines.isEmpty())
                return false;
            return splitAndAssign(getNext());
        }
        
        // This splits & assigns any given line, does not progress the line manager on its own
        public boolean splitAndAssign(String line){
            globalVariables.put("$0", new InterpreterDataType(line));
            String separator = globalVariables.get("FS").value;
            String[] split = line.split(separator);
            
            int i = 1;
            for(String s : split){
                globalVariables.put("$"+ i++, new InterpreterDataType(s));
            }
            // NF = i - 1 (Number of fields)
            globalVariables.put("NF", new InterpreterDataType(Integer.toString(i - 1)));
            return true;
        }
        public boolean editField(int index, String newValue){
            
            if(index == 0){ // Replace whole line
                splitAndAssign(newValue);
                return true;
            }
            
            if(!globalVariables.containsKey("NF") || Double.parseDouble(globalVariables.get("NF").value) < index)
            // Fail if we haven't assigned fields yet, or if we're trying to edit a field that doesn't exist
                return false;
            
            globalVariables.put("$" + index, new InterpreterDataType(newValue));
            return true;
        }
        
        protected String getNext(){
            // NR++ (Number of records)
            globalVariables.put("NR", new InterpreterDataType(Integer.toString(Integer.parseInt(globalVariables.get("NR").value) + 1)));
            // FNR ++ (File Number of Records)
            globalVariables.put("FNR", new InterpreterDataType(Integer.toString(Integer.parseInt(globalVariables.get("FNR").value) + 1)));
            return this.lines.remove(0);
        }
        
    }
    
    private static HashMap<String, InterpreterDataType> globalVariables = new HashMap<>();
    private HashMap<String, FunctionDefinitionNode> functions = new HashMap<>();

    private LineManager lineManager;
    private ProgramNode program;
    private String[] knownArgs = {"FS", "OFMT", "OFS"};

    
    public Interpreter(ProgramNode program, Path fileArg, HashMap<String, String> otherArgs) throws IOException {
        initGlobals(otherArgs);
        System.out.printf("New interpreter with argument \"%s\" created, globals cleared\n", fileArg);
        globalVariables = new HashMap<>();
        if(fileArg != null)
            globalVariables.put("FILENAME", new InterpreterDataType(fileArg.toString()));
        else 
            globalVariables.put("FILENAME", new InterpreterDataType(""));
        setDefaults();
        if(fileArg != null)
            lineManager = new LineManager(Files.readAllLines(fileArg));
        else
            lineManager = new LineManager(List.of());
        this.program = program;

    }
    
    public Interpreter(ProgramNode program, Path fileArg) throws IOException {
        this(program, fileArg, new HashMap<>());
    }

    public Interpreter(ProgramNode program, HashMap<String, String> awkArgs){
        initGlobals(awkArgs);
        System.out.println("New interpreter created, globals cleared");
        globalVariables = new HashMap<>();
        globalVariables.put("FILENAME", new InterpreterDataType(""));
        setDefaults();
        lineManager = new LineManager(List.of());
        this.program = program;
    }
    
    public Interpreter(ProgramNode program){
        this(program, new HashMap<>());
    }
    
    public Interpreter(List<String> debugLines){
        System.out.println("New debug interpreter created, globals cleared");
        globalVariables = new HashMap<>();
        globalVariables.put("FILENAME", new InterpreterDataType("STDIN"));
        setDefaults();
        this.lineManager = new LineManager(debugLines);
    }
    
    private void initGlobals(HashMap<String, String> awkArgs){
        for(String arg : knownArgs){
            if(awkArgs.containsKey(arg))
                globalVariables.put(arg, new InterpreterDataType(awkArgs.get(arg)));
        }
    }
    
    // TODO: Change from public once full functionality is implemented
    public void setFunctions(LinkedList<FunctionDefinitionNode> functions){
        for(FunctionDefinitionNode function : functions)
            this.functions.put(function.getName(), function);
    }
    
    private void setDefaults(){
        globalVariables.putIfAbsent("FS", new InterpreterDataType(" "));
        globalVariables.putIfAbsent("OFMT", new InterpreterDataType("%.6g"));
        globalVariables.putIfAbsent("OFS", new InterpreterDataType("\n"));
        populateKnownFunctions();
    }
    
    public void changeFile(Path path) throws IOException {
        lineManager.switchFile(Files.readAllLines(path));
    }
    
    public void interpretProgram(){
        
        for(FunctionDefinitionNode func: program.getFunctions())
            functions.put(func.getName(), func);
        
        for(BlockNode block: program.getBegin())
            evaluateBlock(block, null).rejectLoopControl("Cannot use break or continue outside of a loop, in BEGIN block");
        lineManager.handleNextLine();

        do
            for(BlockNode block: program.getOther())
                evaluateBlock(block, null).rejectLoopControl("Cannot use break or continue outside of a loop, in \"other\" block");
        while(lineManager.handleNextLine());
        
        for(BlockNode block: program.getEnd())
            evaluateBlock(block, null).rejectLoopControl("Cannot use break or continue outside of a loop, in END block");
        
    }
    
    public ReturnType evaluateStatement(StatementNode statement, HashMap<String,InterpreterDataType> locals){
        if(statement instanceof ASTnode syntax){
        // If, for, while...
            return evaluateSyntax(syntax, locals);
        } else if(statement instanceof AssignmentNode assignment){
            return evaluateAssignment(assignment, locals);
        } else if(statement instanceof FunctionCallNode functionCall){
            ReturnType returned = evaluateCall(functionCall, locals);
            if(returned.isType(ReturnType.Control.RETURN) || returned.isType(ReturnType.Control.NORMAL))
                return new ReturnType(ReturnType.Control.NORMAL, functionCall.reportPosition());
            else
                throw new AwkInterpreterException("Function did not return control properly. Did you use break or continue outside of a loop? By %s".formatted(functionCall.reportPosition()));
        } else 
            throw new AwkInterpreterException("Expected statement, by %s".formatted(statement.reportPosition()));
    }
    
    private ReturnType evaluateSyntax(ASTnode syntax, HashMap<String, InterpreterDataType> locals){
        
        // Check every ASTnode and move onto the correct function/return the corresponding signal
        if(syntax instanceof ASTnode.IfNode ifNode){
            return evaluateIf(ifNode, locals);
        } else if(syntax instanceof ASTnode.WhileNode whileNode){
            return evaluateWhile(whileNode, locals);
        } else if(syntax instanceof ASTnode.ForNode forNode) {
            return evaluateFor(forNode, locals);
        } else if(syntax instanceof ASTnode.DeleteNode deleteNode) {
            return evaluateDelete(deleteNode, locals);
        } else if(syntax instanceof ASTnode.ReturnNode returnNode) {
            return evaluateReturn(returnNode, locals);
        } else if(syntax instanceof ASTnode.BreakNode) {
            return new ReturnType(ReturnType.Control.BREAK, syntax.reportPosition());
        } else if(syntax instanceof ASTnode.ContinueNode) {
            return new ReturnType(ReturnType.Control.CONTINUE, syntax.reportPosition());
        } else
            throw new RuntimeException("Unrecognized syntax type, did the dev finish implementing it? By %s".formatted(syntax.reportPosition()));
        
    }

    private ReturnType evaluateDelete(ASTnode.DeleteNode delete, HashMap<String, InterpreterDataType> locals){
        InterpreterDataType target = getIDT(delete.target, locals);
        Collection<Node> indices = delete.indices;

        if(target instanceof InterpreterArrayDataType array){
            HashMap<String, InterpreterDataType> arrayData = array.getArrayValue();
            for(Node index : indices){
                InterpreterDataType indexData = getIDT(index, locals);
                if(!arrayData.containsKey(indexData.value))
                    throw new AwkIndexOutOfBoundsException(String.format("Index %s out of bounds for array %s, by %s", indexData.value, delete.target, delete.reportPosition()));
                arrayData.remove(indexData.value);
            }
        } else
            throw new AwkIllegalArgumentException("Cannot delete from non-array variable, by %s".formatted(delete.reportPosition()));

        return new ReturnType(ReturnType.Control.NORMAL, delete.reportPosition());
    }

    private ReturnType evaluateReturn(ASTnode.ReturnNode returnNode, HashMap<String, InterpreterDataType> locals){
        return new ReturnType(getIDT(returnNode.value, locals).value, returnNode.reportPosition());
    }
    
    private ReturnType evaluateIf(ASTnode.IfNode ifNode, HashMap<String, InterpreterDataType> locals){
        InterpreterDataType condition = getIDT(ifNode.getCondition(), locals);
        Optional<ASTnode.IfNode> elseNode;
        boolean trueCase = asBoolean(condition.value);
        
        if(trueCase)
            return evaluateBlock(ifNode.getStatements(), locals);
        else if((elseNode = ifNode.getElse()).isPresent())
            return evaluateIf(elseNode.get(), locals);

        return new ReturnType(ReturnType.Control.NORMAL, ifNode.reportPosition());
    }

    private ReturnType evaluateFor(ASTnode.ForNode forNode, HashMap<String, InterpreterDataType> locals){
        if(forNode.forIn)
            return evaluateForIn(forNode, locals);

        // Initialize section: for(_;;)
        // i=...
        getIDT(forNode.getInit(), locals);


        Node condition = forNode.getCondition();
        InterpreterDataType conditionData;
        BlockNode block = forNode.getStatements();

        ReturnType result;
        while(asBoolean((conditionData = getIDT(condition, locals)).value)) /*for(;_;)*/ {

            if((result = evaluateBlock(block, locals)).controlType == ReturnType.Control.BREAK)
                break;
            else if(result.controlType == ReturnType.Control.RETURN)
                return result;
            // Update section: for(;;_)
            //i=...
            getIDT(forNode.getUpdate(), locals);


        }

        return new ReturnType(ReturnType.Control.NORMAL, forNode.reportPosition());
    }

    private ReturnType evaluateForIn(ASTnode.ForNode forNode, HashMap<String, InterpreterDataType> locals){
        String memberName = forNode.getMember().getName();
        InterpreterDataType data = getIDT(forNode.getCollection(), locals);
        if(!(data instanceof InterpreterArrayDataType array))
            throw new AwkIllegalArgumentException("Cannot iterate over non-array, by %s".formatted(forNode.reportPosition()));
        BlockNode block = forNode.getStatements();

        HashMap<String, InterpreterDataType> scope = (locals == null) ? globalVariables : locals;
        ReturnType result;

        for(Map.Entry<String, InterpreterDataType> entry : array.getArrayValue().entrySet()){
            scope.put(memberName, entry.getValue());

            if((result = evaluateBlock(block, locals)).controlType == ReturnType.Control.BREAK)
                break;
            else if(result.controlType == ReturnType.Control.RETURN)
                return result;
        }

        return new ReturnType(ReturnType.Control.NORMAL, forNode.reportPosition());
    }

    private ReturnType evaluateDoWhile(ASTnode.WhileNode whileNode, HashMap<String, InterpreterDataType> locals){
        InterpreterDataType condition;
        boolean trueCase;
        BlockNode block = whileNode.getStatements();
        ReturnType result;

        do{
            if((result = evaluateBlock(block, locals)).controlType == ReturnType.Control.BREAK)
                break;
            else if(result.controlType == ReturnType.Control.RETURN)
                return result;
            condition = getIDT(whileNode.getCondition(), locals);
            trueCase = asBoolean(condition.value);
        } while(trueCase);

        return new ReturnType(ReturnType.Control.NORMAL, whileNode.reportPosition());
    }

    private ReturnType evaluateWhile(ASTnode.WhileNode whileNode, HashMap<String, InterpreterDataType> locals){
        if(whileNode.doWhile)
            return evaluateDoWhile(whileNode, locals);
        InterpreterDataType condition = getIDT(whileNode.getCondition(), locals);
        boolean trueCase = asBoolean(condition.value);
        BlockNode block = whileNode.getStatements();
        ReturnType result;

        while(trueCase){
            if((result = evaluateBlock(block, locals)).controlType == ReturnType.Control.BREAK)
                break;
            else if(result.controlType == ReturnType.Control.RETURN)
                return result;
            condition = getIDT(whileNode.getCondition(), locals);
            trueCase = asBoolean(condition.value);
        }

        return new ReturnType(ReturnType.Control.NORMAL, whileNode.reportPosition());
    }
    
    private ReturnType evaluateBlock(BlockNode block, HashMap<String, InterpreterDataType> locals){
        Optional<Node> condition;
        InterpreterDataType conditionData;
        boolean shouldRun = true;

        if((condition = block.getCondition()).isPresent()){
            if(condition.get() instanceof RegexNode regex)
            // Translates RegexNode[regex] into OperationNode[$0 ~ regex]
                conditionData = getIDT(new OperationNode(new FieldReferenceNode(new ConstantNode<Integer>(0)), OperationNode.Operation.MATCH, regex), locals);
            else
                conditionData = getIDT(condition.get(), locals);

            shouldRun = asBoolean(conditionData.value);
        }

        if(shouldRun)
            return evaluateStatements(block.getStatements(), locals);
        else
            return new ReturnType(ReturnType.Control.NORMAL, block.getStatements().get(0).reportPosition());
    }

    private ReturnType evaluateStatements(List<StatementNode> statements, HashMap<String,InterpreterDataType> locals){
        if(statements.isEmpty())
            throw new AwkInterpreterException("Expected statements, empty code blocks are invalid.");
        ReturnType currentValue;
        String position = statements.get(0).reportPosition(); // Intellij was scared this would stay unassigned, so...
        for(StatementNode statement : statements){
            currentValue = evaluateStatement(statement, locals);
            if(!currentValue.isType(ReturnType.Control.NORMAL)) // break, continue, return...
                return currentValue;
            position = statement.reportPosition();
        }
        
        return new ReturnType(ReturnType.Control.NORMAL, position);
    }
    
    private ReturnType evaluateCall(FunctionCallNode call, HashMap<String,InterpreterDataType> locals){
        
        if(!functions.containsKey(call.getName()))
            throw new AwkInterpreterException(String.format("Function %s not defined, by %s", call.getName(), call.reportPosition()));
        
        FunctionDefinitionNode func = functions.get(call.getName());
        try {
            if (func instanceof BuiltInFunctionDefinitionNode builtIn)
                return evaluateBuiltIn(builtIn, call, locals);
        } catch(AwkInterpreterException e){
            throw new AwkInterpreterException("Error while executing built-in function %s. By %s".formatted(func.getName(), call.reportPosition()), e);

        }
        HashMap<String,InterpreterDataType> scope = (locals == null) ? globalVariables : locals;
        scope.putAll(collectArgs(func.getName(), func.getParameterNames(), call, locals));

        return evaluateStatements(func.getStatements(), scope);
        
    }
    
    private HashMap<String, InterpreterDataType> collectArgs(String funcName, LinkedList<String> parameterNames, FunctionCallNode call, HashMap<String,InterpreterDataType> locals) {
        // Built-in functions don't use this one, maybe could be refactored but the small differences are annoying enough to push that decision to later

        HashMap<String, InterpreterDataType> args = new HashMap<>();
        LinkedList<Node> arguments = call.getArguments();

        Iterator<String> paramNameIterator = parameterNames.iterator();
        if (arguments.size() < parameterNames.size())
            throw new AwkIllegalArgumentException("Too few arguments for %s, by %s".formatted(funcName, call.reportPosition()));

        HashMap<String, InterpreterDataType> array = new HashMap<>(); // This and i are for variadic arguments
        int i = 1;
        for (Node arg : arguments)
            if (paramNameIterator.hasNext())
                args.put(paramNameIterator.next(), getIDT(arg, locals));
            else
            // Variadic arguments
                array.put(Integer.toString(i++), getIDT(arg, locals));
            
        if(!array.isEmpty())    
            args.put(funcName, new InterpreterArrayDataType(array)); // Creates an array with the functions name holding all the variadic arguments. AWK is weird.
        return args;
    }
    
    private ReturnType evaluateBuiltIn(BuiltInFunctionDefinitionNode builtIn, FunctionCallNode call, HashMap<String, InterpreterDataType> locals) {

        LinkedList<Node> argumentNodes = call.getArguments();
        HashMap<String, InterpreterDataType> scope = (locals == null) ? globalVariables : locals;
        // Gather args
        HashMap<String, InterpreterDataType> args = new HashMap<>();
        HashMap<String, String> varToParam = new HashMap<>(); // Map variable name -> variable parameter name
        // The above map is used to change variables that are meant to be mutable

        Iterator<Node> argNodeIterator = argumentNodes.iterator();
        Node currentNode;
        if (builtIn.isVariadic()) {
            int i = 1;
            while (argNodeIterator.hasNext()) {
                currentNode = argNodeIterator.next();
                args.put(Integer.toString(i++), getIDT(currentNode, scope));
            }
        } else for (LinkedList<String> parameterSet : builtIn.getAcceptedParameterNames()) {
            for (String parameter : parameterSet) {
                if (!argNodeIterator.hasNext()) {
                    break; // This parameter set is invalid (too few arguments)
                }

                currentNode = argNodeIterator.next();

                if (parameter.matches("^var.*")) {
                    // For built-in functions, I made all mutable parameters start with var
                    if (!(currentNode instanceof VariableReferenceNode variableRef))
                        break; // This parameter set is invalid (non-variable passed to var parameter)
                    varToParam.put(variableRef.getName(), parameter);
                    try {
                        args.put(parameter, evaluateVariableRef(variableRef, scope));
                    } catch (AwkInterpreterException ignored) {
                        args.put(parameter, new InterpreterDataType(""));
                        // TODO: see if the empty string breaks anything
                    }
                } else {
                    if (currentNode instanceof RegexNode regex)
                        currentNode = regex.getGeneralized();
                    args.put(parameter, getIDT(currentNode, scope));
                }
            }

            if (args.size() == parameterSet.size())
                break; // We found a valid parameter set
            else {
                // This parameter set is invalid (too many arguments, or missing variable parameter)
                args = new HashMap<>(); // Reset everything we collected or used ( what a waste :/ )
                varToParam = new HashMap<>();
                argNodeIterator = argumentNodes.iterator();
            }
        }

        if (args.isEmpty() && !builtIn.getParameterNames().isEmpty())
            throw new AwkIllegalArgumentException("No valid parameter set found for built-in function %s. Double check parameter count and if there are any variable parameters expected. By %s".formatted(builtIn.getName(), call.reportPosition()));

        // Run code
        String output;
        try {
            output = builtIn.getExecute().apply(args);
        } catch (Exception e) {
            throw new AwkInterpreterException("Error while executing built-in function %s. By %s".formatted(builtIn.getName(), call.reportPosition()), e);
        }
        // Update all mutable arguments
        for (String variableName : varToParam.keySet())
            scope.put(variableName, args.get(varToParam.get(variableName)));

        return new ReturnType(output, call.reportPosition());
    }
    public InterpreterDataType getIDT(Node node, HashMap<String, InterpreterDataType> locals){
        if(node instanceof RegexNode)
            throw new RuntimeException("Regex literals are only valid in the condition of conditional control blocks, or passed into certain built-in functions"); // TODO: don't make yourself a liar

        // IntelliJ is suggesting I change this into some pattern switch statement thing, that isn't even normally supported by the java version I'm on lol.
        if (node instanceof AssignmentNode assignment) {
            return evaluateAssignment(assignment, locals).expectData("Assignment as expression must result in a value, by %s".formatted(assignment.reportPosition()));
        } else if (node instanceof ConstantNode<?> constant) {
            return evaluateConstant(constant);
        } else if (node instanceof FunctionCallNode functionCall) {
            return evaluateCall(functionCall, locals).expectData("Function call as expression must result in a value");
        } else if (node instanceof TernaryNode ternary) {
            return evaluateTernary(ternary, locals);
        } else if (node instanceof VariableReferenceNode variableRef) {
            return evaluateVariableRef(variableRef, locals);
        } else if (node instanceof OperationNode operation) {
            return evaluateOperation(operation, locals);
        } else {
            throw new RuntimeException("Unknown node type");
        }

    }
    
    private InterpreterDataType evaluateTernary(TernaryNode node, HashMap<String, InterpreterDataType> scope){
        InterpreterDataType condition = getIDT(node.getCondition(), scope);
        if(asBoolean(condition.value))
            return getIDT(node.getTrueCase(), scope);
        else
            return getIDT(node.getFalseCase(), scope);
    }
    
    private InterpreterDataType evaluateOperation(OperationNode operation, HashMap<String, InterpreterDataType> scope){
        Node left = operation.getLeft();
        Optional<Node> right = Optional.empty();
        if((right = operation.getRight()).isPresent())
            right = operation.getRight();
        
        
        // Get data in left and right
        InterpreterDataType leftData = getIDT(left, scope);
        InterpreterDataType rightData = null;
        if(right.isPresent())
            if(right.get() instanceof RegexNode regex && (operation.isOp(OperationNode.Operation.MATCH) || operation.isOp(OperationNode.Operation.NOTMATCH)))
            // Regex literals are accepted in this case, so we get its more agreeable twin to pass through getIDT in its place
                rightData = getIDT(regex.getGeneralized(), scope);
            else
                rightData = getIDT(right.get(), scope);
        else{
        // Single operand operations, if no right is present it never escapes from here
            switch(operation.getOperation()){
                case NOT -> {
                    if(asBoolean(leftData.value))
                        return new InterpreterDataType("0");
                    else
                        return new InterpreterDataType("1");
                }
                case UNARYNEG -> {
                    try{
                        return new InterpreterDataType(Double.toString(-Double.parseDouble(leftData.value)));
                    } catch (NumberFormatException e){
                        throw new AwkIllegalArgumentException("UNARYNEG operator requires numeric operand");
                    }
                }
                case UNARYPOS -> { // TODO: maybe make this less like eating glass
                    try{
                        return new InterpreterDataType(Double.toString(Double.parseDouble(leftData.value)));
                    } catch (NumberFormatException maybeNumberFollowedByCharacters){
                        String extractedNumber = leftData.value.strip();
                        // Repeatedly trim off the last character and return the first number we get.
                        while(!extractedNumber.isEmpty()){
                            try{
                                return new InterpreterDataType(Double.toString(Double.parseDouble(extractedNumber)));
                            } catch (NumberFormatException e){
                                extractedNumber = extractedNumber.substring(0, extractedNumber.length() - 1);
                            }
                        }
                        // No number at all
                        return new InterpreterDataType("0");
                    }
                }
                case POSTINCREMENT, PREINCREMENT -> {
                    try{
                        return new InterpreterDataType(Double.toString(Double.parseDouble(leftData.value) + 1));
                    } catch (NumberFormatException e){
                        throw new AwkIllegalArgumentException("INCREMENT operator requires numeric operand");
                    }
                }
                case POSTDECREMENT, PREDECREMENT -> {
                    try{
                        return new InterpreterDataType(Double.toString(Double.parseDouble(leftData.value) - 1));
                    } catch (NumberFormatException e){
                        throw new AwkIllegalArgumentException("DECREMENT operator requires numeric operand");
                    }
                }
                default -> {
                    throw new RuntimeException("Unrecognized single operand operation");
                }
            }
        }
        
        // Now we have both left and right
        
        if(rightData == null) // Safety check
            throw new RuntimeException("Right data ended up null while evaluating operation, this should be impossible");
        Double leftDouble, rightDouble;
        
        // Get doubles if possible
        try {
            leftDouble = Double.parseDouble(leftData.value);
            rightDouble = Double.parseDouble(rightData.value);
        } catch (NumberFormatException e){
            leftDouble = null;
            rightDouble = null;
        }
        
        // Get boolean interpretation (we already have the doubles stored here, so forget the helper functions)
        Boolean leftBool = null, rightBool = null;
        try {
            leftBool = asBoolean(leftData.value);
            rightBool = asBoolean(rightData.value);
        } catch (IncompatibleTypeException e){
            leftBool = null;
            rightBool = null; // Just ensure both are null, so we only have to check one
        }
        
        switch(operation.getOperation()){
            case EQUAL -> {
                if(leftDouble != null)
                    return new InterpreterDataType(booleanAsString(leftDouble.equals(rightDouble)));
                return new InterpreterDataType(booleanAsString(leftData.value.equals(rightData.value)));
            }
            case NOTEQUAL -> {
                if(leftDouble != null)
                    return new InterpreterDataType(booleanAsString(!leftDouble.equals(rightDouble)));
                return new InterpreterDataType(booleanAsString(!leftData.value.equals(rightData.value)));
            }
            case LESSTHAN -> {
                if(leftDouble != null)
                    return new InterpreterDataType(booleanAsString(leftDouble < rightDouble));
                else 
                    return new InterpreterDataType(booleanAsString(leftData.value.compareTo(rightData.value) < 0));
            }
            case LESSOREQUAL -> {
                if(leftDouble != null)
                    return new InterpreterDataType(booleanAsString(leftDouble <= rightDouble));
                else 
                    return new InterpreterDataType(booleanAsString(leftData.value.compareTo(rightData.value) <= 0));
            }
            case GREATERTHAN -> {
                if(leftDouble != null)
                    return new InterpreterDataType(booleanAsString(leftDouble > rightDouble));
                else 
                    return new InterpreterDataType(booleanAsString(leftData.value.compareTo(rightData.value) > 0));
            }
            case GREATEROREQUAL -> {
                if(leftDouble != null)
                    return new InterpreterDataType(booleanAsString(leftDouble >= rightDouble));
                else 
                    return new InterpreterDataType(booleanAsString(leftData.value.compareTo(rightData.value) >= 0));
            }
            case AND -> {
                if(leftBool == null)
                    throw new AwkIllegalArgumentException("AND operator requires boolean operands");
                return new InterpreterDataType(booleanAsString(leftBool && rightBool));
            }
            case OR -> {
                if(leftBool == null)
                    throw new AwkIllegalArgumentException("OR operator requires boolean operands");
                return new InterpreterDataType(booleanAsString(leftBool || rightBool));
            }
            case MATCH -> {
                return new InterpreterDataType(booleanAsString(Pattern.matches(rightData.value, leftData.value)));
            }
            case NOTMATCH -> {
                return new InterpreterDataType(booleanAsString(!Pattern.matches(rightData.value, leftData.value)));
            }
            case IN -> {
                if(right.get() instanceof OperationNode rightOperation && rightOperation.isOp(OperationNode.Operation.IN)){
                // Multidimensional case


                    if(!asBoolean(rightData.value))
                        return new InterpreterDataType("0");
                    InterpreterDataType indexData = getIDT(rightOperation.getLeft(), scope);


                    InterpreterArrayDataType finalArray;
                    if(rightOperation.getRight().get() instanceof OperationNode innerOperation && innerOperation.isOp(OperationNode.Operation.IN)) { // Guaranteed get() cause of operation type IN
                        LinkedList<Node> indices = new LinkedList<Node>(List.of(rightOperation.getLeft())); // We need to store indices for later, starting with the inner in-operation's index.

                        OperationNode currentNode = innerOperation;
                        Optional<Node> nextNode = currentNode.getRight();
                        do{
                            if(nextNode.isPresent() && nextNode.get() instanceof OperationNode nextOperation && nextOperation.isOp(OperationNode.Operation.IN)){
                                indices.add(currentNode.getLeft());
                                currentNode = nextOperation;
                                nextNode = currentNode.getRight();
                            } else {
                                indices.add(currentNode.getLeft());
                                break;
                            }
                        }
                        while (nextNode.isPresent());

                        if(currentNode.getRight().isEmpty() || !((getIDT(currentNode.getRight().get(), scope) instanceof InterpreterArrayDataType array)))
                            throw new AwkIllegalArgumentException("IN operator requires array operand");
                        // Loop thru saved indices and unnest the array
                        InterpreterDataType nextData = array;
                        finalArray = array;
                        while(!indices.isEmpty()){
                            String indexValue = parseIndexValue(getIDT(indices.removeLast(), scope));
                            if(!((nextData = ((InterpreterArrayDataType) nextData).getArrayValue().get(indexValue)) instanceof InterpreterArrayDataType arrayData))
                                return new InterpreterDataType("0");
                            finalArray = arrayData;
                        }

                        return new InterpreterDataType(booleanAsString(finalArray.getArrayValue().containsKey(parseIndexValue(leftData)) && asBoolean(rightData.value)));

                    } else if(getIDT(rightOperation.getRight().get(), scope) instanceof InterpreterArrayDataType arrayData) {
                        if (!(arrayData.getArrayValue().get(parseIndexValue(indexData)) instanceof InterpreterArrayDataType array))
                            throw new AwkIllegalArgumentException("IN operator requires array operand");
                        finalArray = array;
                    } else
                        throw new AwkIllegalArgumentException("IN operator requires array operand");

                    return new InterpreterDataType(booleanAsString(finalArray.getArrayValue().containsKey(parseIndexValue(leftData)) && asBoolean(rightData.value)));
                }
                // Single dimensional case
                if(!(rightData instanceof InterpreterArrayDataType array))
                    throw new AwkIllegalArgumentException("IN operator requires array operand");

                return new InterpreterDataType(booleanAsString(array.getArrayValue().containsKey(parseIndexValue(leftData))));
            }
            case CONCATENATION -> {
                return new InterpreterDataType(leftData.value + rightData.value);
            }
            case ADD -> {
                if(leftDouble != null)
                    return new InterpreterDataType(Double.toString(leftDouble + rightDouble));
                else 
                    throw new AwkIllegalArgumentException("ADD operator requires numeric operand");
            }
            case SUBTRACT -> {
                if(leftDouble != null)
                    return new InterpreterDataType(Double.toString(leftDouble - rightDouble));
                else 
                    throw new AwkIllegalArgumentException("SUBTRACT operator requires numeric operand");
            }
            case MULTIPLY -> {
                if(leftDouble != null)
                    return new InterpreterDataType(Double.toString(leftDouble * rightDouble));
                else 
                    throw new AwkIllegalArgumentException("MULTIPLY operator requires numeric operand");
            }
            case DIVIDE -> {
                if(leftDouble != null)
                    return new InterpreterDataType(Double.toString(leftDouble / rightDouble));
                else 
                    throw new AwkIllegalArgumentException("DIVIDE operator requires numeric operand");
            }
            case MODULO -> {
                if(leftDouble != null)
                    return new InterpreterDataType(Double.toString(leftDouble % rightDouble));
                else 
                    throw new AwkIllegalArgumentException("MODULO operator requires numeric operand");
            }
            case EXPONENT -> {
                if(leftDouble != null)
                    return new InterpreterDataType(Double.toString(Math.pow(leftDouble, rightDouble)));
                else 
                    throw new AwkIllegalArgumentException("EXPONENTIATION operator requires numeric operand");
            }
            default -> {
                throw new RuntimeException("Operation not implemented yet");
            }
        }
        
    }
    
    private InterpreterDataType evaluateFieldReference(FieldReferenceNode node, HashMap<String, InterpreterDataType> locals){
        Node index = node.getIndex().orElseThrow(); // getIndex should never fail here
        InterpreterDataType indexData = getIDT(index, locals);
        int fieldIndex;
        try {
            fieldIndex = (int) Double.parseDouble(indexData.value); // any decimal value is truncated
        } catch (NumberFormatException e){
            throw new AwkIllegalArgumentException("Field index must be numeric");
        }
        
        if(fieldIndex < 0)
            throw new AwkIllegalArgumentException("Field index must be positive");
        if(fieldIndex > Integer.parseInt(globalVariables.get("NF").value))
            throw new AwkIndexOutOfBoundsException(String.format("Index %d out of bounds for %d fields", fieldIndex, Integer.parseInt(globalVariables.get("NF").value)));
        
        return globalVariables.get("$" + fieldIndex);
    }
    private InterpreterDataType evaluateVariableRef(VariableReferenceNode node, HashMap<String, InterpreterDataType> locals){
        String name = node.getName();
        Optional<Node> index;
        if(node instanceof AssignmentNode assignment)
            return evaluateAssignment(assignment, locals);
        
        if(node instanceof FieldReferenceNode field) {
            return evaluateFieldReference(field, locals);
        }
        
        HashMap<String, InterpreterDataType> scope = (locals == null) ? globalVariables : locals;
        
        // Get the variable data
        InterpreterDataType variableData;
        if(scope.containsKey(name))
            variableData = scope.get(name);
        else if(globalVariables.containsKey(name)) {
            variableData = globalVariables.get(name);
        } else
            throw new AwkInterpreterException(String.format("Variable %s not defined", name));
        String indexValue;
        // Handle the array (or field reference) case
        if((index = node.getIndex()).isPresent()){
            if(variableData instanceof InterpreterArrayDataType arrayData){
                InterpreterDataType indexData = getIDT(index.get(), scope);
                indexValue = parseIndexValue(indexData);

                HashMap<String, InterpreterDataType> array = arrayData.getArrayValue();
                if(!array.containsKey(indexValue))
                    throw new AwkIndexOutOfBoundsException(String.format("Index %s out of bounds for array %s", indexValue, name));

                Optional<Node> nextIndex = index.get().getNext();
                while(nextIndex.isPresent()){
                // Multi-dimensional array reference
                    if(!(array.get(indexValue) instanceof InterpreterArrayDataType data))
                        throw new AwkInterpreterException(String.format("Attempted to make array reference to non-array element within within array %s ", name));
                    array = data.getArrayValue();
                    index = nextIndex;
                    indexData = getIDT(index.get(), scope);
                    indexValue = parseIndexValue(indexData);

                    nextIndex = index.get().getNext();
                }

                if(!array.containsKey(indexValue))
                    throw new AwkIndexOutOfBoundsException(String.format("Index %s out of bounds for array %s", indexValue, name));

                return array.get(indexValue);
            } else 
                throw new AwkInterpreterException(String.format("Attempted to make array reference to non-array variable %s", name));
        }
        
        return variableData;
        }
    
    private ReturnType evaluateAssignment(AssignmentNode node, HashMap<String, InterpreterDataType> locals){
        String name = node.getName();
        InterpreterDataType value;
        try {
            value = getIDT(node.getAssignedTo(), locals);
        } catch (RuntimeException e){
            throw new AwkInterpreterException("Invalid assignment by %s, got exception:\n %s".formatted(node.reportPosition(), e));
        }
        boolean postOperation;
        
        // Check to see if we're working with a post-operation, so we can return the correct value
        if(node.getAssignedTo() instanceof OperationNode operation)
            postOperation = operation.getOperation() == OperationNode.Operation.POSTINCREMENT || operation.getOperation() == OperationNode.Operation.POSTDECREMENT;
        else
            postOperation = false;
        
        InterpreterDataType original = null;
        HashMap<String, InterpreterDataType> scope = (locals == null) ? globalVariables : locals;
        
        
        // Ensure that if were doing a post-operation, we have something to return
        // (since post operations return the original value, not the new one)
        

        if (scope.containsKey(name)) {
            original = scope.get(name);
        } else if (globalVariables.containsKey(name)) {
            original = globalVariables.get(name);
        } else if(postOperation)
            throw new AwkInterpreterException(String.format("Variable %s not defined, cannot return value before assignment (post operation). By %s", name, node.reportPosition()));

        Optional<Node> indexNode; // Used for both array and field references
        
        if(node.getTarget() instanceof FieldReferenceNode fieldReference){
        // Field assignment case (note: The order matters! Field references also fit the array case, so we sift them out first.)
            if((indexNode = fieldReference.getIndex()).isEmpty())
                throw new AwkInterpreterException("Field assignment requires index, by %s".formatted(node.reportPosition()));
            InterpreterDataType indexData = getIDT(indexNode.get(), locals);
            int index;
            try {
                index = (int) Double.parseDouble(indexData.value); // parseDouble used because numbers are stored as doubles
            }catch(NumberFormatException e){
                throw new AwkIllegalArgumentException("Field index must be numeric, by %s".formatted(node.reportPosition()));
            }
            if(!lineManager.editField(index, value.value))
                throw new AwkIndexOutOfBoundsException(String.format("Index %d out of bounds for %d fields, by %s", index, Integer.parseInt(globalVariables.get("NF").value), node.reportPosition()));
        } else if((indexNode = node.getTarget().getIndex()).isPresent()){
        // Array assignment case
            HashMap<String, InterpreterDataType> newArray;
            InterpreterDataType indexData = getIDT(indexNode.get(), locals);
            String indexValue;
            try{
                // Floats with zero decimal are the same as ints, so we have to check for that
                indexValue = Double.parseDouble(indexData.value) + "";
                String[] split = indexValue.split("\\.");
                if(Double.parseDouble(split[1]) == 0)
                    indexValue = split[0]; // Truncate decimal if it's 0
            } catch (NumberFormatException e){
                indexValue = indexData.value;
            }
            
            // Get the original array, or create a new one if it doesn't exist
            if(original instanceof InterpreterArrayDataType arrayData)
                newArray = arrayData.getArrayValue();
            else newArray = new HashMap<>();


            if(indexNode.get().getNext().isPresent())
                newArray = handleArrayDimension(newArray, indexNode.get(), value, scope);
            else
                newArray.put(indexValue, value);
            scope.put(name, new InterpreterArrayDataType(newArray));

        } else {
        // Normal assignment case
            if(original instanceof InterpreterArrayDataType arrayData)
                throw new AwkInterpreterException(String.format("Attempted to assign non-array value to array variable %s, by %s", name, node.reportPosition()));
            scope.put(name, value);
        } 
        
        if(postOperation) {
            if(original == null)
                throw new AwkInterpreterException("Post-operation failed to get original value, isn't this impossible? By %s".formatted(node.reportPosition()));
            return new ReturnType(original.value, false, node.reportPosition());
        }
        else 
            return new ReturnType(value.value, false, node.reportPosition()); // Maybe refactor value.value away
    }

    public HashMap<String, InterpreterDataType> handleArrayDimension(HashMap<String, InterpreterDataType> currentArray, Node indexNode, InterpreterDataType value, HashMap<String, InterpreterDataType> scope) {
        if (indexNode.getNext().isEmpty()) {
            currentArray.put(parseIndexValue(getIDT(indexNode, scope)), value);
            return currentArray;
        }

        // Process the current dimension
        String indexValue = parseIndexValue(getIDT(indexNode, scope)); // Method to get the index value from the node
        HashMap<String, InterpreterDataType> nextArray;

        if (!currentArray.containsKey(indexValue)) {
            nextArray = new HashMap<>();
            currentArray.put(indexValue, new InterpreterArrayDataType(nextArray));
        } else if (currentArray.get(indexValue) instanceof InterpreterArrayDataType data) {
            nextArray = data.getArrayValue();
        } else {
            throw new AwkInterpreterException("Non-array element access");
        }
        nextArray = handleArrayDimension(nextArray, indexNode.getNext().get(), value, scope);
        currentArray.put(indexValue, new InterpreterArrayDataType(nextArray));
        // Recursive call for the next dimension
        return currentArray;

    }

    private String parseIndexValue(InterpreterDataType indexData) {
        try {
            String indexValue = Double.toString(Double.parseDouble(indexData.value));
            String[] split = indexValue.split("\\.");
            if (Double.parseDouble(split[1]) == 0) {
                return split[0]; // Truncate decimal if it's 0
            }
            return indexValue;
        } catch (NumberFormatException e) {
            return indexData.value;
        }
    }
    
    private InterpreterDataType evaluateConstant(ConstantNode<?> node){
        return new InterpreterDataType(node.getValue());
    }
    
    private void populateKnownFunctions(){

        // Print
        Function<HashMap<String,InterpreterDataType>,String> executePrint = (HashMap<String,InterpreterDataType> array) -> {
            LinkedList<String> args = new LinkedList<>();

            int i = 1; // 1-indexed
            String data;

            // Collect args 
            while(array.containsKey(Integer.toString(i))) {
                data = array.get(Integer.toString(i++)).value;
                args.add(data);
            }

            // Format args with separator
            StringBuilder output = new StringBuilder();
            for (int j = 1; j < i - 1; j++) // Stops one element early, see comment below
                output.append(args.poll()).append(globalVariables.get("FS").value);
            output.append(args.poll()); // Last one doesn't get separator

            System.out.print(output);

            return output.toString();
        };
        functions.put("print", new BuiltInFunctionDefinitionNode("print", executePrint, true));

        // Printf
        Function<HashMap<String,InterpreterDataType>,String> executePrintf = (HashMap<String,InterpreterDataType> array) -> {
            LinkedList<Object> args = new LinkedList<>();

            int i = 1;
            String format;
            String data;
            // get 1st arg, the format string
            if(array.containsKey(Integer.toString(i))){
                format = array.get(Integer.toString(i++)).value;
            } else {
                throw new AwkIllegalArgumentException("printf requires a format string");
            }

            // get remaining args
            while(array.containsKey(Integer.toString(i))){
                data = array.get(Integer.toString(i)).value;
                    args.add(data);
                i++;
            }

            // Give args the right types (if possible) so that the formatting at the end doesn't fail

            Pattern pattern = Pattern.compile("%[sdfegGoxXcu]"); // all kinds of %s, %d, %f, etc. that I felt like implementing
            LinkedList<String> formatArgs = new LinkedList<>();
            Matcher matcher = pattern.matcher(format);
            while(matcher.find())
                formatArgs.add(matcher.group());

            i = 0;
            for (String currentFormat : formatArgs) {
                switch (currentFormat) {
                    case "%d", "%i", "%x", "%X", "%o" -> {
                        args.set(i, Integer.parseInt(args.get(i).toString()));
                    }
                    case "%u" -> { // %u doesn't exist in java so we fudge it
                        long longValue = Long.parseLong(args.get(i).toString());
                        args.set(i, Long.toUnsignedString(longValue));
                        format = format.replaceAll("%u", "%s");
                    }
                    case "%f", "%e", "%E", "%g", "%G" -> {
                        args.set(i, String.format(currentFormat, Double.parseDouble(args.get(i).toString())));
                    }
                    case "%c" -> {
                        args.set(i, (args.get(i).toString()).toCharArray()[0]);
                    }
                }
                i++;
            }


            System.out.printf(format, args.toArray());
            return String.format(format, args.toArray());

        };

        functions.put("printf", new BuiltInFunctionDefinitionNode("printf", executePrintf, true));

        // Getline
        Function<HashMap<String,InterpreterDataType>,String> executeGetLine = (HashMap<String,InterpreterDataType> args) -> {
            
            if(args.isEmpty())
                return lineManager.handleNextLine() ? "1" : "0";
            else if(!args.containsKey("var"))
                throw new AwkIllegalArgumentException("getline requires either no arguments, or a variable to store into");
            
            try {
                args.put("var", new InterpreterDataType(lineManager.getNext()));
                return "1";
            } catch (IndexOutOfBoundsException e){
                return "0";
            }
            
        };
        
        functions.put("getline", new BuiltInFunctionDefinitionNode("getline", executeGetLine, List.of(
                List.of(), // No args, just move to the next line
                List.of("var") // Store into a variable
        )));

        // Next
        Function<HashMap<String,InterpreterDataType>,String> executeNext = (HashMap<String,InterpreterDataType> noArgs) -> {
            lineManager.handleNextLine();
            return "";
        };

        functions.put("next", new BuiltInFunctionDefinitionNode("next", executeNext, false));

        // Gsub
        Function<HashMap<String,InterpreterDataType>,String> executeGsub = (HashMap<String,InterpreterDataType> args) -> {
            String regex = args.get("regex").value;
            String replacement = args.get("replacement").value;
            String target;
            if (args.containsKey("var")){
                target = args.get("var").value;
                args.put("var", new InterpreterDataType(target = target.replaceAll(regex, replacement)));
            } else {
                target = globalVariables.get("$0").value;
                lineManager.splitAndAssign(target = target.replaceAll(regex, replacement));
            }
            return target;
        };
        
        // Note: gsub and sub have the same argument structure
        List<List<String>> subArgs = List.of(
                List.of("regex", "replacement", "var"),
                List.of("regex", "replacement") // Defaults to $0
        ); 

        functions.put("gsub", new BuiltInFunctionDefinitionNode("gsub", executeGsub, subArgs));

        // Sub
        Function<HashMap<String,InterpreterDataType>,String> executeSub = (HashMap<String,InterpreterDataType> args) -> {
            String regex = args.get("regex").value;
            String replacement = args.get("replacement").value;
            String target;
            
            if(args.containsKey("var")) {
                target = args.get("var").value;
                args.put("var", new InterpreterDataType(target = target.replaceFirst(regex, replacement)));
            } else {
                target = globalVariables.get("$0").value;
                lineManager.splitAndAssign(target = target.replaceFirst(regex, replacement));
            }
            return target;
        };
        
        // identical argNames from above
        
        functions.put("sub", new BuiltInFunctionDefinitionNode("sub", executeSub, subArgs));

        // Match
        Function<HashMap<String,InterpreterDataType>,String> executeMatch = (HashMap<String,InterpreterDataType> args) -> {
            String target = args.get("target").value;
            String regex = args.get("regex").value;

            // Regex matcher gets our match index for free
            Matcher matcher = Pattern.compile(regex).matcher(target);

            if(!matcher.find())
                // No matches, return 0
                return "0";
            int firstMatchAt = matcher.start();


            if(args.containsKey("varArray")){
                HashMap<String, InterpreterDataType> extractedArray;
                if(!(args.get("varArray") instanceof InterpreterArrayDataType arrayData))
                    extractedArray = new HashMap<>();
                else
                    extractedArray = arrayData.getArrayValue();
                int i = 1; // 1-indexed

                for(int j = 0; j <= matcher.groupCount(); j++) //
                // Fill the array with groups
                    extractedArray.put(Integer.toString(i++), new InterpreterDataType(matcher.group(j)));

                args.put("varArray", new InterpreterArrayDataType(extractedArray));
            }
            
            return Integer.toString(firstMatchAt + 1); // 1-indexed
        };
        
        functions.put("match", new BuiltInFunctionDefinitionNode("match", executeMatch, List.of(
                List.of("target", "regex"),
                List.of("target", "regex", "varArray")
        )));

        // Length
        Function<HashMap<String,InterpreterDataType>,String> executeLength = (HashMap<String,InterpreterDataType> args) -> {
           
            InterpreterDataType target;
            if(args.containsKey("target"))
                target = args.get("target");
            else
                target = globalVariables.get("$0");

            if(target instanceof InterpreterArrayDataType array)
                return Integer.toString(array.getArrayValue().size());
            else
                return Integer.toString(target.value.length());
        };
        
        functions.put("length", new BuiltInFunctionDefinitionNode("length", executeLength, List.of(
                List.of("target"),
                List.of() // Defaults to $0
        )));


        // Index
        Function<HashMap<String,InterpreterDataType>,String> executeIndex = (HashMap<String,InterpreterDataType> args) -> {
            String string = args.get("string").value;
            String substring = args.get("substring").value;

            return Integer.toString(string.indexOf(substring) + 1); // 1-indexed
        };
        
        functions.put("index", new BuiltInFunctionDefinitionNode("index", executeIndex, 
                new LinkedList<String>(List.of("string", "substring"))
        )); // Only one valid argument set, no need for a list of lists

        
        // Substr
        Function<HashMap<String,InterpreterDataType>,String> executeSubstr = (HashMap<String,InterpreterDataType> args) -> {
            String string = args.get("string").value;
            int start = (int) Double.parseDouble(args.get("start").value);
            int length = args.containsKey("length") ? (int) Double.parseDouble(args.get("length").value) : string.length();
            // ^ Get length arg (as int) or default to length of string
            
            if(length < 0 || length > string.length())
                throw new AwkIndexOutOfBoundsException("Substring length must be positive and less than the length of the original string");

            return string.substring(start - 1, length + 1); // 1-indexed, the length arg is exclusive
        };

        functions.put("substr", new BuiltInFunctionDefinitionNode("substr", executeSubstr, List.of(
                List.of("string", "start", "length"),
                List.of("string", "start")
        )));
        

        // ToLower
        Function<HashMap<String,InterpreterDataType>,String> executeToLower = (HashMap<String,InterpreterDataType> args) -> {
            String string = args.get("string").value;

            return string.toLowerCase();
        };
        
        functions.put("tolower", new BuiltInFunctionDefinitionNode("tolower", executeToLower, 
                new LinkedList<String>(List.of("string"))
        ));
        

        // ToUpper
        Function<HashMap<String,InterpreterDataType>,String> executeToUpper = (HashMap<String,InterpreterDataType> args) -> {
            String string = args.get("string").value;

            return string.toUpperCase();
        };
        
        functions.put("toupper", new BuiltInFunctionDefinitionNode("toupper", executeToUpper, 
                new LinkedList<String>(List.of("string"))
        ));


    }
    
    public static Optional<InterpreterDataType> getGlobalVariable(String name){
        if(globalVariables.containsKey(name))
            return Optional.of(globalVariables.get(name));
        else
            return Optional.empty();
    }
    
    
    
    public static boolean asBoolean(String value){
        if(value.isEmpty()) // "" is false
            return false;
        ;
        try {
        // If it's a number return that interpretation
            return Double.parseDouble(value) != 0;
        } catch (NumberFormatException e){
        // If it's not, we have a non-empty string (true)
            return true;
        }
        
    }
    
    private static String booleanAsString(Boolean bool){
        return bool ? "1" : "0";
    }

    private static String numberToString(Number number){
        if(number instanceof Double doubleNumber)
            return doubleNumber.toString();
        else if(number instanceof Integer intNumber)
            return intNumber.toString();
        else
            throw new RuntimeException("Unknown number type");
    }


    private static class AwkInterpreterException extends RuntimeException {
        public AwkInterpreterException(String message, Exception cause){
            super(message, cause);
        }
        public AwkInterpreterException(String message){
            super(message);
        }
    }

    private static class AwkIndexOutOfBoundsException extends AwkInterpreterException {
        public AwkIndexOutOfBoundsException(String message){
            super(message);
        }
    }

    private static class AwkIllegalArgumentException extends AwkInterpreterException {
        public AwkIllegalArgumentException(String message){
            super(message);
        }
    }

    private static class IncompatibleTypeException extends AwkInterpreterException {
        public IncompatibleTypeException(String message){
            super(message);
        }
    }
}
