
package com.schibsted.spt.data.jstl2;

import java.io.File;
import java.io.FileReader;
import java.io.StringReader;
import java.io.FileNotFoundException;
import java.util.Map;
import java.util.HashMap;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.schibsted.spt.data.jstl2.impl.*;

public class Parser {

  // this will be replaced with a proper Context. need to figure out
  // relationship between compile-time and run-time context first.
  private static Map<String, Function> functions = new HashMap();
  static {
    functions.put("number", new BuiltinFunctions.Number());
    functions.put("test", new BuiltinFunctions.Test());
    functions.put("capture", new BuiltinFunctions.Capture());
    functions.put("split", new BuiltinFunctions.Split());
  }

  public static Expression compile(File jstl) {
    // FIXME: character encoding bug
    try {
      return compile(new JstlParser(new FileReader(jstl)));
    } catch (FileNotFoundException e) {
      throw new JstlException("Couldn't find file " + jstl);
    }
  }

  public static Expression compile(String jstl) {
    return compile(new JstlParser(new StringReader(jstl)));
  }

  private static Expression compile(JstlParser parser) {
    try {
      parser.Start();

      // the start production always contains an expr, so we just ditch it
      SimpleNode start = (SimpleNode) parser.jjtree.rootNode();
      start.dump(">");

      LetExpression[] lets = buildLets(start);
      SimpleNode expr = getLastChild(start);
      return new ExpressionImpl(lets, node2expr(expr));
    } catch (ParseException e) {
      throw new RuntimeException(e);
    }
  }

  private static ExpressionNode node2expr(SimpleNode node) {
    // top is comparison expression

    ExpressionNode first = node2addexpr(getChild(node, 0));
    if (node.jjtGetNumChildren() == 1) // it's just the base
      return first;

    ExpressionNode second = node2addexpr(getChild(node, 2));

    // get the comparator
    Token comp = getChild(node, 1).jjtGetFirstToken();
    if (comp.kind == JstlParserConstants.EQUALS)
      return new EqualsComparison(first, second);
    else
      throw new RuntimeException("What kind of comparison is this?");
  }

  private static ExpressionNode node2addexpr(SimpleNode node) {
    ExpressionNode first = node2baseExpr(getChild(node, 0));
    if (node.jjtGetNumChildren() == 1) // it's just the base
      return first;

    ExpressionNode second = node2baseExpr(getChild(node, 2));

    // get the operator
    Token comp = getChild(node, 1).jjtGetFirstToken();
    if (comp.kind == JstlParserConstants.PLUS)
      return new PlusOperator(first, second);
    else
      throw new RuntimeException("What kind of comparison is this?");
  }

  private static ExpressionNode node2baseExpr(SimpleNode node) {
    Token token = node.jjtGetFirstToken();
    if (token.kind == JstlParserConstants.LBRACKET ||
        token.kind == JstlParserConstants.LCURLY ||
        token.kind == JstlParserConstants.IF ||
        token.kind == JstlParserConstants.IDENT /* function call */)
      // it's not a token but a production, so we ditch the Expr node
      // and go down to the level below, which holds the actual info
      node = (SimpleNode) node.jjtGetChild(0);

    token = node.jjtGetFirstToken();
    int kind = token.kind;

    if (kind == JstlParserConstants.NULL)
      return new LiteralExpression(NullNode.instance);

    else if (kind == JstlParserConstants.INTEGER) {
      IntNode number = new IntNode(Integer.parseInt(token.image));
      return new LiteralExpression(number);

    } else if (kind == JstlParserConstants.DECIMAL) {
      DoubleNode number = new DoubleNode(Double.parseDouble(token.image));
      return new LiteralExpression(number);

    } else if (kind == JstlParserConstants.STRING)
      return new LiteralExpression(new TextNode(makeString(token)));

    else if (kind == JstlParserConstants.TRUE)
      return new LiteralExpression(BooleanNode.TRUE);

    else if (kind == JstlParserConstants.FALSE)
      return new LiteralExpression(BooleanNode.FALSE);

    else if (kind == JstlParserConstants.DOT ||
             kind == JstlParserConstants.VARIABLE ||
             kind == JstlParserConstants.IDENT)
      return chainable2Expr(node);

    else if (kind == JstlParserConstants.IF) {
      LetExpression[] letelse = null;
      ExpressionNode theelse = null;
      SimpleNode maybeelse = getLastChild(node);
      if (maybeelse.jjtGetFirstToken().kind == JstlParserConstants.ELSE) {
        SimpleNode elseexpr = getLastChild(maybeelse);
        theelse = node2expr(elseexpr);
        letelse = buildLets(maybeelse);
      }

      LetExpression[] thenelse = buildLets(node);

      return new IfExpression(
        node2expr((SimpleNode) node.jjtGetChild(0)),
        thenelse,
        node2expr((SimpleNode) node.jjtGetChild(thenelse.length + 1)),
        letelse,
        theelse
      );

    } else if (kind == JstlParserConstants.LBRACKET)
      return new ArrayExpression(children2Exprs(node));

    else if (kind == JstlParserConstants.LCURLY)
      return buildObject(node);

    else {
      node.dump(">");
      throw new RuntimeException("I'm confused now: " + node.jjtGetNumChildren() + " " + kind);
    }
  }

  private static ExpressionNode chainable2Expr(SimpleNode node) {
    // node: chainable
    Token token = node.jjtGetFirstToken();
    int kind = token.kind;

    // if we start with a dot we can just do the whole thing now
    if (kind == JstlParserConstants.DOT) {
      if (token.next.kind != JstlParserConstants.IDENT &&
          token.next.kind != JstlParserConstants.STRING)
        return new DotExpression(); // there was only a dot

      // ok, there was a key
      return recursivelyBuildDotChain(token, null);
    }

    // need to special-case the first node
    ExpressionNode start;
    if (kind == JstlParserConstants.VARIABLE)
      start = new VariableExpression(token.image.substring(1));

    else if (kind == JstlParserConstants.IDENT) {
      node = descendTo(node, JstlParserTreeConstants.JJTFUNCTIONCALL);

      // function call, where the children are the parameters
      Function func = functions.get(token.image);
      if (func == null)
        throw new JstlException("No such function: '" + token.image + "'");
      start = new FunctionExpression(func, children2Exprs(node));

    } else
      throw new RuntimeException("Now I'm *really* confused!");

    // INVARIANT: at this point token is the last token in the first part
    token = token.next; // step to next part of chain

    // then tack on the rest of the chain
    if (token.kind == JstlParserConstants.DOT)
      return recursivelyBuildDotChain(token, start);
    else
      return start;
  }

  // the token is the DOT, after which there must be an IDENT
  private static ExpressionNode recursivelyBuildDotChain(Token token, ExpressionNode parent) {
    token = token.next; // step to the IDENT

    // first make an expression for us
    String key = token.image; // works fine for IDENT, but not STRING
    if (token.kind == JstlParserConstants.STRING)
      key = makeString(token);
    ExpressionNode dot = new DotExpression(key, parent);

    // check if there is more, if so, build
    if (token.next.kind == JstlParserConstants.DOT)
      dot = recursivelyBuildDotChain(token.next, dot);

    return dot;
  }

  private static String makeString(Token literal) {
    return literal.image.substring(1, literal.image.length() - 1);
  }

  private static ExpressionNode[] children2Exprs(SimpleNode node) {
    ExpressionNode[] children = new ExpressionNode[node.jjtGetNumChildren()];
    for (int ix = 0; ix < node.jjtGetNumChildren(); ix++)
      children[ix] = node2expr((SimpleNode) node.jjtGetChild(ix));
    return children;
  }

  // collects all the 'let' statements as children of this node
  private static LetExpression[] buildLets(SimpleNode parent) {
    // figure out how many lets there are
    int letCount = 0;
    for (int ix = 0; ix < parent.jjtGetNumChildren(); ix++) {
      SimpleNode child = (SimpleNode) parent.jjtGetChild(ix);
      if (child.firstToken.kind == JstlParserConstants.LET)
        letCount++;
    }

    // collect the lets
    int pos = 0;
    LetExpression[] lets = new LetExpression[letCount];
    for (int ix = 0; ix < parent.jjtGetNumChildren(); ix++) {
      SimpleNode node = (SimpleNode) parent.jjtGetChild(ix);
      if (node.firstToken.kind != JstlParserConstants.LET)
        continue;

      Token ident = node.jjtGetFirstToken().next;
      SimpleNode expr = (SimpleNode) node.jjtGetChild(0);
      lets[pos++] = new LetExpression(ident.image, node2expr(expr));
    }
    return lets;
  }

  private static ObjectExpression buildObject(SimpleNode node) {
    int childCount = node.jjtGetNumChildren();

    // collect the lets
    LetExpression[] lets = buildLets(node);

    // build object content
    PairExpression[] children = new PairExpression[childCount - lets.length];
    for (int ix = lets.length; ix < childCount; ix++) {
      SimpleNode pair = (SimpleNode) node.jjtGetChild(ix);
      String key = makeString(pair.jjtGetFirstToken());
      ExpressionNode val = node2expr((SimpleNode) pair.jjtGetChild(0));
      children[ix - lets.length] = new PairExpression(key, val);
    }
    return new ObjectExpression(lets, children);
  }

  private static SimpleNode getChild(SimpleNode node, int ix) {
    return (SimpleNode) node.jjtGetChild(ix);
  }

  private static SimpleNode getLastChild(SimpleNode node) {
    return (SimpleNode) node.jjtGetChild(node.jjtGetNumChildren() - 1);
  }

  private static SimpleNode descendTo(SimpleNode node, int type) {
    if (node.id == type)
      return node;

    return descendTo((SimpleNode) node.jjtGetChild(0), type);
  }
}