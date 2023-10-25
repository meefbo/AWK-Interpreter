import java.util.Collection;
import java.util.List;
import java.util.Optional;

public abstract class ASTnodes extends Node implements StatementNode{
    
    protected Node condition;
    protected BlockNode statements;
    
    ASTnodes(){
        this.condition = new ConstantNode<Boolean>(true);
        this.statements = null;
    }
    ASTnodes(BlockNode statements){
        this.condition = new ConstantNode<Boolean>(true);
        this.statements = statements;
    }
    ASTnodes(Node condition, BlockNode statements){
        this.condition = condition;
        this.statements = statements;
    }
    
    
    public static class IfNode extends ASTnodes {
        private Optional<IfNode> elseNode = Optional.empty();
        
        IfNode(BlockNode statements) {
            super(statements);
        }
        IfNode(Node condition, BlockNode statements) {
            super(condition, statements);
        }
        IfNode(Node condition, BlockNode statements, IfNode elseNode) {
            super(condition, statements);
            this.elseNode = Optional.of(elseNode);
        }
        
        public void setElse(IfNode next) {
            elseNode = Optional.of(next);
        }

        public Optional<IfNode> getElse(){
            return elseNode;
        }
        
        public String toString(){
            if(elseNode.isPresent())
                return String.format("IfNode: if(%s) %s else %s", condition, statements, elseNode.get());
            return String.format("IfNode: if(%s) %s", condition, statements);
        }
        
        public boolean equals(Object o){
            if(o == this)
                return true;
            if(!(o instanceof IfNode))
                return false;
            IfNode other = (IfNode) o;
            if(elseNode.isPresent())
                return other.elseNode.isPresent() && condition.equals(other.condition) && statements.equals(other.statements) && elseNode.get().equals(other.elseNode.get());
            return condition.equals(other.condition) && statements.equals(other.statements);
        }
    }
    
    public static class WhileNode extends ASTnodes {
        
        boolean doWhile = false;
        WhileNode(Node condition, BlockNode statements) {
            super(condition, statements);
        }
        
        WhileNode(Node condition, BlockNode statements, boolean doWhile) {
            super(condition, statements);
            this.doWhile = doWhile;
        }
        
        public String toString(){
            if(doWhile)
                return String.format("WhileNode: do %s while(%s)", statements, condition);
            return String.format("WhileNode: while(%s) %s", condition, statements);
        }
        
        public boolean equals(Object o){
            if(o == this)
                return true;
            if(!(o instanceof WhileNode))
                return false;
            WhileNode other = (WhileNode) o;
            return doWhile == other.doWhile && condition.equals(other.condition) && statements.equals(other.statements);
        }
    }
    
    public static class ForNode extends ASTnodes {
        private Node init;
        private Node update;
        private boolean forIn = false;
        private Node member;
        private Node collection;
        
        ForNode(Node init, Node condition, Node update,BlockNode statements) {
            super(condition, statements);
            this.init = init;
            this.update = update;
        }
        
        ForNode(Node member, Node collection, BlockNode statements){
            super(statements);
            this.member = member;
            this.collection = collection;
            this.forIn = true;
        }
        
        
        public String toString(){
            if(forIn)
                return String.format("ForNode: for(%s in %s) %s", member, collection, statements);
            return String.format("ForNode: for(%s; %s; %s) %s", init, condition, update, statements);
        }
        
        public boolean equals(Object o){
            if(o == this)
                return true;
            if(!(o instanceof ForNode))
                return false;
            ForNode other = (ForNode) o;
            if(forIn != other.forIn)
                return false;
            
            if(forIn)
                return member.equals(other.member) && collection.equals(other.collection) && statements.equals(other.statements);
            return init.equals(other.init) && condition.equals(other.condition) && update.equals(other.update) && statements.equals(other.statements);
        }
        
    }
    
    public static class ContinueNode extends ASTnodes {
        ContinueNode() {
            super();
        }

        public String toString(){
            return "ContinueNode";
        }

        public boolean equals(Object o){
            if(o == this)
                return true;
            if(!(o instanceof ContinueNode))
                return false;
            return true;
        }
    }
    
    public static class BreakNode extends ASTnodes {
        BreakNode() {
            super();
        }
        
        public String toString(){
            return "BreakNode";
        }
        
        public boolean equals(Object o){
            if(o == this)
                return true;
            if(!(o instanceof BreakNode))
                return false;
            return true;
        }
    }
    
    public static class DeleteNode extends ASTnodes {
        VariableReferenceNode target;
        Collection<Node> indices;
        
        DeleteNode(VariableReferenceNode target) {
            super();
            this.target = target;
            this.indices = List.of();
        }
        
        DeleteNode(VariableReferenceNode target, Collection<Node> indices) {
            super();
            this.target = target;
            this.indices = indices;
        }
        
        public String toString(){
            return String.format("DeleteNode: %s", target);
        }
        
        public boolean equals(Object o){
            if(o == this)
                return true;
            if(!(o instanceof DeleteNode))
                return false;
            DeleteNode other = (DeleteNode) o;
            return target.equals(other.target) && indices.equals(other.indices);
        }
    }
    
    public static class ReturnNode extends ASTnodes {
        Node value;
        public ReturnNode(Node value) {
            super();
            this.value = value;
        }
        
        public String toString(){
            return String.format("ReturnNode: %s", value);
        }
        
        public boolean equals(Object o){
            if(o == this)
                return true;
            if(!(o instanceof ReturnNode))
                return false;
            ReturnNode other = (ReturnNode) o;
            return value.equals(other.value);
        }
    }
}