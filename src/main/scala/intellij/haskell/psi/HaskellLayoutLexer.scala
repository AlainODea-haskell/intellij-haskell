package intellij.haskell.psi

import com.intellij.lexer.{Lexer, LexerBase}
import com.intellij.psi.tree.{IElementType, TokenSet}
import com.intellij.util.containers.IntStack

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

/**
  * This is the main lexer. It wraps the underlying lexer generated by Flex
  * to synthesize special tokens based on whitespace-sensitive layout rules
  * in the layout-based languages. This makes it possible to write a
  * traditional parser using JFlex/Grammar-Kit.
  *
  * This is a modified version of:
  * https://github.com/klazuka/intellij-elm/blob/master/src/main/kotlin/org/elm/lang/core/lexer/ElmLayoutLexer.kt
  */
class HaskellLayoutLexer(private val lexer: Lexer,
                         private val endOfLine: IElementType,
                         private val layoutStart: IElementType,
                         private val layoutSeparator: IElementType,
                         private val layoutEnd: IElementType,
                         private val nonCodeTokens: TokenSet,
                         private val layoutCreatingTokens: TokenSet,
                         private val letInTokens: LetIn) extends LexerBase {

  private case class Line(var columnWhereCodeStarts: Option[Int] = None)

  private case class Token(elementType: Option[IElementType],
                           start: Int,
                           end: Int,
                           column: Int,
                           line: Line
                          ) {
    override def toString = s"${elementType.toString} ($start, $end)"

    val isEOF: Boolean = elementType.isEmpty
    def isCode: Boolean = elementType.exists(et => !nonCodeTokens.contains(et)) && !isEOF

    def isNextLayoutLine: Boolean = isCode && line.columnWhereCodeStarts.contains(column)
  }

  private val tokens = new ArrayBuffer[Token](100)
  private var currentTokenIndex = 0

  private def currentToken = tokens.lift(currentTokenIndex)

  def start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
    require(startOffset == 0, "does not support incremental lexing: startOffset must be 0")
    require(initialState == 0, "does not support incremental lexing: initialState must be 0")

    // Start the incremental lexer
    lexer.start(buffer, startOffset, endOffset, initialState)

    doLayout()
    currentTokenIndex = 0
  }

  def getState: Int = lexer.getState

  def getBufferSequence: CharSequence = lexer.getBufferSequence

  def getBufferEnd: Int = lexer.getBufferEnd

  def getTokenType: IElementType = currentToken.flatMap(_.elementType).orNull

  def getTokenStart: Int = currentToken.map(_.start).getOrElse(null.asInstanceOf[Int])

  def getTokenEnd: Int = currentToken.map(_.end).getOrElse(null.asInstanceOf[Int])

  def advance() {
    if (currentToken.exists(ct => !ct.isEOF && ct.elementType.isDefined)) {
      currentTokenIndex += 1
    }
  }

  private def slurpTokens(): Unit = {
    var currentColumn = 0

    @tailrec
    def doIt(line: Line): Unit = {
      val token = Token(Option(lexer.getTokenType), lexer.getTokenStart, lexer.getTokenEnd, currentColumn, line)
      tokens += token

      if (line.columnWhereCodeStarts.isEmpty && token.isCode) {
        line.columnWhereCodeStarts = Some(currentColumn)
      }

      currentColumn += token.end - token.start

      if (!token.isEOF) {
        if (token.elementType.contains(endOfLine)) {
          currentColumn = 0
        }

        lexer.advance()

        doIt(Line())
      }
    }

    doIt(Line())
  }

  private trait State

  /**
    * Waiting for the first line of code inside a let/in or case/of in order to open a new section.
    */
  private case object WaitingForLayout extends State

  /**
    * Looking to emit virtual delimiters between declarations at the same indent level
    * and closing out sections when appropriate.
    */
  private case object Normal extends State

  /**
    * The real initial state. We don't work on layout until we've encountered the first one.
    */
  private case object NotYetStarted extends State


  private def doLayout() {
    slurpTokens()

    // initial state
    var i = 0
    var state: State = NotYetStarted
    val indentStack = new IndentStack()
    indentStack.push(-1) // top-level is an implicit section

    for (token <- tokens) {

      state match {
        case NotYetStarted =>
          if (token.elementType.exists(layoutCreatingTokens.contains)) {
            state = WaitingForLayout
          }
        case WaitingForLayout =>
          if (token.isCode && token.column > indentStack.peek()) {
            tokens.insert(i, virtualToken(layoutStart, tokens(if (i <= 0) 0 else i - 1)))
            i += 1
            indentStack.push(token.column)
            if (!token.elementType.exists(layoutCreatingTokens.contains)) {
              state = Normal
            }
          } else if (token.isNextLayoutLine && (token.column <= indentStack.peek())) {
            // The program is malformed: most likely because the new section is empty
            // (the user is still editing the text) or they did not indent the section.
            // The empty section case is a common workflow, so we must handle it by bailing
            // out of section building and re-process the token in the 'Normal' state.
            // If, instead, the problem is that the user did not indent the text,
            // tough luck (although we may want to handle this better in the future).
            state = Normal
            i -= 1
          }
        case Normal =>
          if (token.elementType.exists(layoutCreatingTokens.contains)) {
            state = WaitingForLayout
          }
          if (token.isNextLayoutLine) {
            i = insideLayout(i, token, indentStack)
          }
          if (isSingleLineLetIn(i, tokens, letInTokens)) {
            tokens.insert(i, virtualToken(layoutEnd, tokens(i - 1)))
            i += 1
            indentStack.pop()
          }
      }

      if (token.elementType.isEmpty)
        while (indentStack.pop() >= 0) {
          tokens += virtualToken(layoutEnd, token)
          i += 1
        }

      i += 1
    }
  }


  /**
    * Extracted from [doLayout] to reduce indentation.
    * We want to insert virtual tokens immediately after the newline that follows
    * the last code token. This is important so that:
    *
    * (1) trailing spaces at the end of the declaration are part of the declaration
    * (2) top-level comments that follow the declaration are NOT part of the declaration
    *
    * Note that a virtual token has to appear after a whitespace token, since the real token
    * is combined with the virtual token during parsing (their text ranges overlap).
    */
  private def insideLayout(i: Int, token: Token, indentStack: IndentStack): Int = {
    val insertAt = {
      for {
        k <- ((i - 1) to 1 by -1).view
        if tokens(k).isCode
        m <- ((k + 1) until (i + 1)).view
        if tokens(m).elementType.contains(endOfLine)
      } yield {
        m
      }
    }.headOption.getOrElse(i)

    val precedingToken = tokens(insertAt)

    @tailrec
    def doIt(iA: Int, mutI: Int): Int = {
      if (!indentStack.empty()) {
        if (token.column == indentStack.peek()) {
          tokens.insert(
            iA, virtualToken(layoutSeparator, precedingToken))
          mutI + 1
        } else if (token.column < indentStack.peek()) {
          tokens.insert(iA, virtualToken(layoutEnd, precedingToken))
          indentStack.pop()
          doIt(iA + 1, mutI + 1)
        } else
          mutI
      } else {
        mutI
      }
    }

    doIt(insertAt, i)
  }

  /**
    * Many languages allows for a let/in expression on a single line:
    * e.g. ```foo = let x = 0 in x + 1```
    * I don't know why you would ever do this, but some people do:
    * https://github.com/klazuka/intellij-elm/issues/20#issuecomment-374843581
    * *
    * If we didn't have special handling for it, the `let` section wouldn't
    * get closed-out until a subsequent line with less indent, which would be wrong.
    */
  private def isSingleLineLetIn(index: Int, tokens: Seq[Token], letInTokens: LetIn): Boolean = {
    val token = tokens(index)
    if (token.elementType.contains(letInTokens.inToken)) {
      val thisLine = token.line

      @tailrec
      def doit(i: Int): Boolean = {
        val j = i - 1
        val token = tokens(j)
        if (token.elementType.contains(letInTokens.letToken)) {
          true
        } else {
          if (token.line == thisLine && j < tokens.size) {
            doit(j)
          } else {
            false
          }
        }
      }

      doit(index)
    } else {
      false
    }
  }

  private def virtualToken(elementType: IElementType, precedesToken: Token) = {
    Token(
      elementType = Option(elementType),
      start = precedesToken.start,
      end = precedesToken.start, // yes, this is intentional
      column = precedesToken.column,
      line = precedesToken.line
    )
  }

  /**
    * In a well-formed program, there would be no way to underflow the indent stack,
    * but this lexer will be asked to lex malformed/partial programs, so we need
    * to guard against trying to use the stack when it's empty.
    */
  private class IndentStack extends IntStack {
    override def peek(): Int = if (super.empty()) -1 else super.peek()

    override def pop(): Int = if (super.empty()) -1 else super.pop()
  }

}


case class LetIn(letToken: IElementType, inToken: IElementType)

