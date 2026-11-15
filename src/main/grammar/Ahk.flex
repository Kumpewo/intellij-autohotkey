// Lexer of AutoHotkey v2 for JFlex (generates _AhkLexer); works together with Ahk.bnf.
package de.kumpewo.autohotkey.parser;

import com.intellij.lexer.FlexLexer;
import com.intellij.psi.tree.IElementType;
import de.kumpewo.autohotkey.psi.AhkTypes;

%%

%class _AhkLexer
%implements FlexLexer
%unicode
// Keywords are case-insensitive in AHK: "if" also matches IF, If ...
%ignorecase
%function advance
%type IElementType
%eof{
  return;
%eof}

%{
  /**
   * Operator at the end of a line => the following line boundary is a continuation (not an end of statement).
   * Looks after the token across spaces/tabs (and a comment) up to the line break.
   */
  private IElementType op(IElementType type) {
      int i = zzMarkedPos;
      int start = i;
      while (i < zzEndRead && (zzBuffer.charAt(i) == ' ' || zzBuffer.charAt(i) == '\t')) i++;
      if (i < zzEndRead) {
          char c = zzBuffer.charAt(i);
          if (c == '\r' || c == '\n' || (c == ';' && i > start)) {
              yybegin(IN_CONTINUATION);
          }
      }
      return type;
  }

  /**
   * True if the character before the current token ends an operand (name, number, closing bracket, quote, percent
   * sign): a dot there is member access ({@code a.5}, {@code f().5}), not the start of a number like {@code .5}.
   */
  private boolean afterOperand() {
      if (zzStartRead == 0) return false;
      char c = zzBuffer.charAt(zzStartRead - 1);
      return Character.isLetterOrDigit(c) || c == '_' || c == ')' || c == ']' || c == '}' || c == '"' || c == '\''
              || c == '%';
  }
%}

CRLF                = [\r\n]+
WHITE_SPACE         = [ \t]+

ASSIGN_OP           = ":=" | "+=" | "-=" | "*=" | "//=" | "/=" | ".=" | "|=" | "&=" | "^=" | "<<=" | ">>>=" | ">>=" | "??="

NUMBER              = [0-9]+(\.[0-9]+)? | 0[xX][0-9a-fA-F]+
// v2: letters, digits, _ and all non-ASCII characters
IDENTIFIER          = [a-zA-Z_\u0080-\uFFFF][a-zA-Z0-9_\u0080-\uFFFF]*

// A string is ONE token. The escape character is the backtick. A line break does not end a string
// "validly"; then UNCLOSED applies (without swallowing the line break itself).
STRING_DOUBLE       = \" ( [^\"`\r\n] | "`" [^\r\n] )* \"
STRING_SINGLE       = '  ( [^'`\r\n]  | "`" [^\r\n] )* '
UNCLOSED_DOUBLE     = \" ( [^\"`\r\n] | "`" [^\r\n] )* "`"?
UNCLOSED_SINGLE     = '  ( [^'`\r\n]  | "`" [^\r\n] )* "`"?

// Continuation section (multi-line string), started by an OPEN string:
//   x := "            <- string ends at the end of the line
//   (                 <- next line except empty/comment lines: starts with "(" (options = normal text)
//     any text
//   )"               <- some following line starts with ")" and closes with the quote
// The whole thing is ONE token (STRING). If the closing line is missing, it stays an UNCLOSED_STRING.
EOL                 = \r\n | \r | \n
CS_SKIP             = [ \t]* ( ";" [^\r\n]* )? {EOL}
CS_OPEN             = [ \t]* "(" [^\r\n]* {EOL}
CS_TEXT             = [ \t]* ( [^ \t)\r\n] [^\r\n]* )? {EOL}
CS_DOUBLE           = \" ( [^\"`\r\n] | "`" [^\r\n] )* {EOL} {CS_SKIP}* {CS_OPEN} {CS_TEXT}* [ \t]* ")" [^\"\r\n]* \"
CS_SINGLE           = '  ( [^'`\r\n]  | "`" [^\r\n] )* {EOL} {CS_SKIP}* {CS_OPEN} {CS_TEXT}* [ \t]* ")" [^'\r\n]* '

// Hotkey text up to the FIRST "::", without quotes (otherwise strings containing "::" eat the line).
// A ";" with whitespace before it (or at the start of the line) starts a comment and never belongs to the hotkey:
// "f() { ; ::" is not a hotkey. Without whitespace before it ";" is a normal character (`;:: or a;::).
HK_CHAR             = [^:\r\n\"' \t;]
HK_UNIT             = {HK_CHAR} ";"* | [ \t]+ | ":" ( [^:\r\n\"' \t] ";"* | [ \t]+ )
HOTKEY_TEXT         = {HK_UNIT}+

// YYINITIAL means "start of the text" and "start of a line" here.
// Hotstring head: :options:abbreviation::   (options and abbreviation may be empty / contain single ':')
HOTSTRING_HEAD      = ":" [^:\r\n]* ":" ( [^:\r\n] | ":" [^:\r\n] )* "::"
// With the option X the rest of the line is code, otherwise replacement text.
HOTSTRING_HEAD_X    = ":" [^:\r\n]* [xX] [^:\r\n]* ":" ( [^:\r\n] | ":" [^:\r\n] )* "::"

// Line starts with an operator => continuation line. Deliberately only operators that can never be the
// start of a hotkey ("," only without a following ":", otherwise it is the hotkey ,::)
// (+a:: ^a:: !a:: *a:: ~a:: ... are hotkeys).
// ":" only with a following space (ternary); a hotstring never starts with ": ".
CONT_START          = [ \t]* ( ( "and" | "or" | "is" | "in" | "contains" ) [^a-zA-Z0-9_] | "&&" | "||" | "??" | "?" [^:] | ":" [ \t] | "." [ \t] | "," [^:] )

// Empty and comment lines: meaningless for AHK, also between a line and its continuation line.
// This includes block comments at the start of a line (also indented and multi-line), after "*/" only whitespace.
SKIP_LINE           = [ \t]* ( ";" [^\r\n]* | "/*" ~"*/" [ \t]* )? {EOL}

%states YYINITIAL, IN_LINE, IN_COMMENT, IN_BAD_COMMENT, IN_DIRECTIVE, IN_HOTSTRING_TEXT, IN_CONTINUATION, IN_CONCAT_DOT
%states IN_SKIP_LINES, IN_SKIP_COMMENT, IN_AFTER_HOTKEY

%%

<YYINITIAL> {
    ";" [^\r\n]* {
          yybegin(IN_LINE);
          return AhkTypes.LINE_COMMENT;
      }

    // Block comment at the start of a line (also indented). The indentation is its own token (not part of the
    // comment), so that the formatter can set it; otherwise every reformat added one more indent.
    [ \t]+ / "/*" { return com.intellij.psi.TokenType.WHITE_SPACE; }
    "/*" {
        yybegin(IN_COMMENT);
        return AhkTypes.BLOCK_COMMENT;
    }

    // Order matters: the X variant first (same length -> first rule wins)
    {HOTSTRING_HEAD_X} {
        yybegin(IN_LINE);
        return AhkTypes.HOTSTRING;
    }

    {HOTSTRING_HEAD} {
        yybegin(IN_HOTSTRING_TEXT);
        return AhkTypes.HOTSTRING;
    }

    // "/* a::b */" or "    /* a::b" is a block comment, not a hotkey (the hotkey match is just longer).
    // Leading whitespace becomes its own token, then the rule for "/*" above applies.
    {HOTKEY_TEXT} "::" {
          CharSequence t = yytext();
          int i = 0;
          while (i < t.length() && (t.charAt(i) == ' ' || t.charAt(i) == '\t')) i++;
          if (i + 1 < t.length() && t.charAt(i) == '/' && t.charAt(i + 1) == '*') {
              if (i > 0) {
                  yypushback(t.length() - i);
                  return com.intellij.psi.TokenType.WHITE_SPACE;
              }
              yypushback(t.length() - 2);
              yybegin(IN_COMMENT);
              return AhkTypes.BLOCK_COMMENT;
          }
          // After "::" it is no longer the start of a line: "F1:: /* x */" is a BAD_COMMENT, not a block comment.
          yybegin(IN_AFTER_HOTKEY);
          return AhkTypes.HOTKEY;
    }

    // For #Space:: HOTKEY is longer and wins, for #Include it stays a directive.
    "#" [a-zA-Z_][a-zA-Z0-9_]* {
          yybegin(IN_DIRECTIVE);
          return AhkTypes.DIRECTIVE_NAME;
      }

    [^] {
          yybegin(IN_LINE);
          yypushback(1);
      }
}

// Line break before an operator: whitespace, not an end of statement (the parser does not see it).
// Empty and comment lines may be in between; IN_SKIP_LINES reads them.
<YYINITIAL, IN_LINE> {
    {CRLF} / {SKIP_LINE}* {CONT_START}  {
              yybegin(IN_SKIP_LINES);
              return com.intellij.psi.TokenType.WHITE_SPACE;
          }
}

// Empty and comment lines inside a continued line (after an operator at the end of a line or before
// a line that starts with an operator). Line breaks are whitespace here. The whitespace before the
// code of the continuation line stays for IN_LINE (important for " . ").
<IN_SKIP_LINES> {
    [ \t]* ";" [^\r\n]*   { return AhkTypes.LINE_COMMENT; }
    [ \t]* {CRLF}         { return com.intellij.psi.TokenType.WHITE_SPACE; }
    // Block comment at the start of a skipped line (also indented): continues here afterwards
    [ \t]+ / "/*"         { return com.intellij.psi.TokenType.WHITE_SPACE; }
    "/*"                  { yybegin(IN_SKIP_COMMENT); return AhkTypes.BLOCK_COMMENT; }
    // ". " at column 0 of a continuation line is a concatenation (CONT_START), although no whitespace precedes it
    "." / [ \t]           { yybegin(IN_LINE); return op(AhkTypes.CONCAT_DOT); }
    [^]                   { yybegin(IN_LINE); yypushback(1); }
}
<IN_SKIP_COMMENT> {
    "*/"    { yybegin(IN_SKIP_LINES); return AhkTypes.BLOCK_COMMENT; }
    [^*]+   { return AhkTypes.BLOCK_COMMENT; }
    "*"     { return AhkTypes.BLOCK_COMMENT; }
}

// Directly after "F1::": "a::;" (followed only by whitespace or " ;comment") is a remapping to the key ";"
// (REMAP_KEY). Otherwise ";" is a comment there ("F1::;x"). Continues in IN_LINE afterwards.
<IN_AFTER_HOTKEY> {
    ";" [^\r\n]*   {
          yybegin(IN_LINE);
          CharSequence t = yytext();
          int i = 1;
          while (i < t.length() && (t.charAt(i) == ' ' || t.charAt(i) == '\t')) i++;
          if (i == t.length() || (i > 1 && t.charAt(i) == ';')) {
              yypushback(t.length() - 1);
              return AhkTypes.REMAP_KEY;
          }
          return AhkTypes.LINE_COMMENT;
      }
    [^]            { yybegin(IN_LINE); yypushback(1); }
}

// " . " = concatenation: whitespace before it, whitespace or end of line after it.
// The whitespace stays its own token; only the dot becomes CONCAT_DOT (otherwise DOT = member access).
<IN_LINE> {
    {WHITE_SPACE} / "." [ \t\r\n]  { yybegin(IN_CONCAT_DOT); return com.intellij.psi.TokenType.WHITE_SPACE; }
    // Block comment in the middle of a line: AHK does not recognize it, hence BAD_COMMENT (error in AhkBadCommentAnnotator).
    // Must come before the general WHITE_SPACE rule (same length -> first rule wins).
    {WHITE_SPACE} / "/*"           { yybegin(IN_BAD_COMMENT); return com.intellij.psi.TokenType.WHITE_SPACE; }
}
// One token up to and including "*/", also across lines; without "*/" up to the end of the file.
<IN_BAD_COMMENT> {
    "/*" ~"*/"                              { yybegin(IN_LINE); return AhkTypes.BAD_COMMENT; }
    "/*" ( [^*] | "*"+ [^*/] )* "*"*        { yybegin(IN_LINE); return AhkTypes.BAD_COMMENT; }
    [^]                                     { yybegin(IN_LINE); yypushback(1); }
}
<IN_CONCAT_DOT> {
    "."      { yybegin(IN_LINE); return op(AhkTypes.CONCAT_DOT); }
    [^]      { yybegin(IN_LINE); yypushback(1); }
}

// Line break after an operator (the state is set in op())
<IN_CONTINUATION> {
    [ \t]+ ";" [^\r\n]*  { return AhkTypes.LINE_COMMENT; }
    {WHITE_SPACE}         { return com.intellij.psi.TokenType.WHITE_SPACE; }
    {CRLF}                { yybegin(IN_SKIP_LINES); return com.intellij.psi.TokenType.WHITE_SPACE; }
    [^]                   { yybegin(IN_LINE); yypushback(1); }
}

// Replacement text of a hotstring: rest of the line up to a comment.
// ";" with whitespace before it starts a real comment and does not belong to the text (as everywhere).
// Without whitespace before it ("a;b", "`;", "::x::;") ";" is a normal character. Trailing whitespace is not part of it.
<IN_HOTSTRING_TEXT> {
    [ \t]+ ";" [^\r\n]*                         { return AhkTypes.LINE_COMMENT; }
    ( [^ \t\r\n] | [ \t]+ [^ \t;\r\n] )+        { return AhkTypes.HOTSTRING_TEXT; }
    [ \t]+                                      { return com.intellij.psi.TokenType.WHITE_SPACE; }
}

<YYINITIAL, IN_LINE, IN_DIRECTIVE, IN_HOTSTRING_TEXT> {
    {CRLF}  {
              yybegin(YYINITIAL);
              return AhkTypes.CRLF;
          }
}

<YYINITIAL, IN_LINE> {
    {WHITE_SPACE}     { return com.intellij.psi.TokenType.WHITE_SPACE; }
}

// Arguments of a directive: rest of the line up to a comment.
<IN_DIRECTIVE> {
    ";" [^\r\n]*      { return AhkTypes.LINE_COMMENT; }
    [^\r\n;]+         { return AhkTypes.DIRECTIVE_ARGS; }
}

<IN_LINE> {
    [ \t]+ ";" [^\r\n]*            { return AhkTypes.LINE_COMMENT; }

    "\f"                            { return com.intellij.psi.TokenType.BAD_CHARACTER; }

    "{"   { return AhkTypes.L_BRACE; }
    "}"   { return AhkTypes.R_BRACE; }
    "["   { return AhkTypes.L_BRACKET; }
    "]"   { return AhkTypes.R_BRACKET; }
    "("   { return AhkTypes.L_PAREN; }
    ")"   { return AhkTypes.R_PAREN; }
    ","   { return op(AhkTypes.COMMA); }

    // --- Operators. The longest match wins (":=" before ":", "==" before "=", "**" before "*" ...)
    {ASSIGN_OP}   { return op(AhkTypes.ASSIGN_OP); }
    "++" | "--"   { return AhkTypes.INCREMENT_OP; }
    "=>"          { return op(AhkTypes.FAT_ARROW); }
    "**"          { return op(AhkTypes.POW); }
    "//"          { return op(AhkTypes.FLOOR_DIV); }
    "+"           { return op(AhkTypes.PLUS); }
    "-"           { return op(AhkTypes.MINUS); }
    "*"           { return op(AhkTypes.STAR); }
    "/"           { return op(AhkTypes.SLASH); }
    "=="          { return op(AhkTypes.EQ_EQ); }
    "="           { return op(AhkTypes.EQ); }
    "!=" "="?     { return op(AhkTypes.NOT_EQ); }
    "<="          { return op(AhkTypes.LE); }
    ">="          { return op(AhkTypes.GE); }
    "<"           { return op(AhkTypes.LT); }
    ">"           { return op(AhkTypes.GT); }
    "~="          { return op(AhkTypes.REGEX_MATCH); }
    "<<" | ">>>" | ">>"  { return op(AhkTypes.SHIFT_OP); }
    "&&"          { return op(AhkTypes.AND_AND); }
    "||"          { return op(AhkTypes.OR_OR); }
    "??"          { return op(AhkTypes.COALESCE); }
    "?"           { return op(AhkTypes.QUESTION); }
    ":"           { return AhkTypes.COLON; }
    "."           { return op(AhkTypes.DOT); }
    "!"           { return AhkTypes.BANG; }
    "~"           { return AhkTypes.TILDE; }
    "&"           { return AhkTypes.AMP; }
    "|"           { return op(AhkTypes.BAR); }
    "^"           { return op(AhkTypes.CARET); }
    "%"           { return AhkTypes.PERCENT; }

    // --- Keywords: must come BEFORE {IDENTIFIER} (same length -> first rule wins).
    "if"        { return AhkTypes.KEYWORD_IF; }
    "else"      { return AhkTypes.KEYWORD_ELSE; }
    "while"     { return AhkTypes.KEYWORD_WHILE; }
    "loop"      { return AhkTypes.KEYWORD_LOOP; }
    "for"       { return AhkTypes.KEYWORD_FOR; }
    "until"     { return AhkTypes.KEYWORD_UNTIL; }
    "return"    { return AhkTypes.KEYWORD_RETURN; }
    "break"     { return AhkTypes.KEYWORD_BREAK; }
    "continue"  { return AhkTypes.KEYWORD_CONTINUE; }
    "try"       { return AhkTypes.KEYWORD_TRY; }
    "catch"     { return AhkTypes.KEYWORD_CATCH; }
    "finally"   { return AhkTypes.KEYWORD_FINALLY; }
    "throw"     { return AhkTypes.KEYWORD_THROW; }
    "as"        { return AhkTypes.KEYWORD_AS; }
    "and"       { return op(AhkTypes.KEYWORD_AND); }
    "or"        { return op(AhkTypes.KEYWORD_OR); }
    "not"       { return op(AhkTypes.KEYWORD_NOT); }
    "is"        { return op(AhkTypes.KEYWORD_IS); }
    "in"        { return op(AhkTypes.KEYWORD_IN); }
    "contains"  { return op(AhkTypes.KEYWORD_CONTAINS); }
    "local" | "global" | "static"            { return AhkTypes.KEYWORD_DECL; }
    // "isset" deliberately not: IsSet is a normal built-in function (functions.txt)
    "true" | "false" | "unset" | "super" | "this"  { return AhkTypes.KEYWORD_LITERAL; }
    "goto"                                   { return AhkTypes.KEYWORD_GOTO; }
    "switch"                                 { return AhkTypes.KEYWORD_SWITCH; }
    "case"                                   { return AhkTypes.KEYWORD_CASE; }
    "class" | "struct"                       { return AhkTypes.KEYWORD_CLASS; }
    "extends"                                { return AhkTypes.KEYWORD_EXTENDS; }

    {NUMBER}                 { return AhkTypes.NUMBER; }
    // Float without leading zero (.5). Directly after an operand the dot stays member access (a.5 = DOT NUMBER).
    "." [0-9]+               { if (afterOperand()) { yypushback(yylength() - 1); return op(AhkTypes.DOT); }
                               return AhkTypes.NUMBER; }
    {IDENTIFIER}             { return AhkTypes.IDENTIFIER; }

    {CS_DOUBLE} | {CS_SINGLE}               { return AhkTypes.STRING; }
    {STRING_DOUBLE} | {STRING_SINGLE}       { return AhkTypes.STRING; }
    {UNCLOSED_DOUBLE} | {UNCLOSED_SINGLE}   { return AhkTypes.UNCLOSED_STRING; }

    // Everything else is an error in AHK v2 outside of strings.
    .                        { return com.intellij.psi.TokenType.BAD_CHARACTER; }
}

<IN_COMMENT> {
    "*/" {
        yybegin(IN_LINE);
        return AhkTypes.BLOCK_COMMENT;
    }
    [^*]+  { return AhkTypes.BLOCK_COMMENT; }
    "*"    { return AhkTypes.BLOCK_COMMENT; }
}

[^] { return com.intellij.psi.TokenType.BAD_CHARACTER; }
