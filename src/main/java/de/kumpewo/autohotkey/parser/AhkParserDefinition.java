package de.kumpewo.autohotkey.parser;

import com.intellij.lang.ASTNode;
import com.intellij.lang.ParserDefinition;
import com.intellij.lang.PsiParser;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.project.Project;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.TokenSet;
import de.kumpewo.autohotkey.AhkFile;
import de.kumpewo.autohotkey.AhkLanguage;
import de.kumpewo.autohotkey.psi.AhkTokenSets;
import de.kumpewo.autohotkey.psi.AhkTypes;
import org.jetbrains.annotations.NotNull;

/**
 * Connects lexer, parser and PSI of the language (extension {@code lang.parserDefinition}).
 * Only {@code WHITE_SPACE} is whitespace: line breaks ({@code CRLF}) are real tokens because a line end ends a
 * statement.
 */
final class AhkParserDefinition implements ParserDefinition {

    public static final IFileElementType FILE = new IFileElementType(AhkLanguage.INSTANCE);

    /** The lexer generated from {@code Ahk.flex}. */
    @NotNull
    @Override
    public Lexer createLexer(Project project) {
        return new AhkLexerAdapter();
    }

    /** Comment tokens; the parser skips them. */
    @NotNull
    @Override
    public TokenSet getCommentTokens() {
        return AhkTokenSets.COMMENTS;
    }

    /** Only spaces and tabs; {@code CRLF} is not whitespace. */
    @Override
    public @NotNull TokenSet getWhitespaceTokens() {
        return TokenSet.create(TokenType.WHITE_SPACE);
    }

    /** No string literal elements are reported to the platform. */
    @NotNull
    @Override
    public TokenSet getStringLiteralElements() {
        return TokenSet.EMPTY;
    }

    /** The parser generated from {@code Ahk.bnf}. */
    @NotNull
    @Override
    public PsiParser createParser(final Project project) {
        return new AhkParser();
    }

    /** Element type of the file node. */
    @NotNull
    @Override
    public IFileElementType getFileNodeType() {
        return FILE;
    }

    /** Creates the PSI file. */
    @NotNull
    @Override
    public PsiFile createFile(@NotNull FileViewProvider viewProvider) {
        return new AhkFile(viewProvider);
    }

    /** Creates the PSI element for an AST node (generated factory). */
    @NotNull
    @Override
    public PsiElement createElement(ASTNode node) {
        return AhkTypes.Factory.createElement(node);
    }

}
