
/*
 * Copyright (C) 2015 Archie L. Cobbs. All rights reserved.
 */

package io.permazen.parse.expr;

import io.permazen.JTransaction;
import io.permazen.UntypedJObject;
import io.permazen.core.ObjId;
import io.permazen.core.TypeNotInSchemaVersionException;
import io.permazen.parse.ObjIdParser;
import io.permazen.parse.ParseException;
import io.permazen.parse.ParseSession;
import io.permazen.parse.ParseUtil;
import io.permazen.parse.Parser;
import io.permazen.parse.SpaceParser;
import io.permazen.util.ParseContext;

import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.dellroad.stuff.java.Primitive;
import org.dellroad.stuff.string.StringEncoder;

/**
 * Parses literal values.
 *
 * <p>
 * Includes these special extensions:
 * <ul>
 *  <li>Variable references, e.g., {@code $foo}</li>
 *  <li>Object ID literals, e.g., <code>@fc21bf6d8930a215</code></li>
 * </ul>
 */
public class LiteralExprParser implements Parser<Node> {

    public static final LiteralExprParser INSTANCE = new LiteralExprParser();

    static final String IDENTS_AND_DOTS_PATTERN = ParseUtil.IDENT_PATTERN + "\\s*(?:\\.\\s*" + ParseUtil.IDENT_PATTERN + ")*";
    static final String CLASS_NAME_PATTERN = "(?:" + IDENTS_AND_DOTS_PATTERN + ")\\s*(?:(?:\\[\\s*\\]\\s*)+)?";

    private final SpaceParser spaceParser = new SpaceParser();

    @Override
    public Node parse(ParseSession session, ParseContext ctx, boolean complete) {
        final int start = ctx.getIndex();

        // Try to match null
        if (ctx.tryPattern("null(?!\\p{javaJavaIdentifierPart})") != null)
            return new LiteralNode(null);

        // Try to match boolean
        final Matcher booleanMatch = ctx.tryPattern("(false|true)(?!\\p{javaJavaIdentifierPart})");
        if (booleanMatch != null)
            return new LiteralNode(Boolean.valueOf(booleanMatch.group()));

        // Try class literal
        final Matcher classMatch = ctx.tryPattern("(" + CLASS_NAME_PATTERN + ")\\.\\s*class(?!\\p{javaJavaIdentifierPart})");
        if (classMatch != null) {
            final String className = classMatch.group(1).replaceAll("\\s+", "");
            return ClassNode.parse(ctx, className, true);
        }

        // Try to match int or long literal
        try {
            final int radix;
            Matcher matcher;
            if ((matcher = ctx.tryPattern("(?si)(\\+|-|)(?:0x|#)(\\p{XDigit}+)(L)?")) != null)
                radix = 16;
            else if ((matcher = ctx.tryPattern("(?si)(\\+|-|)0b([01]+)(L)?")) != null)
                radix = 2;
            else if ((matcher = ctx.tryPattern("(?si)(\\+|-|)(0[0-7]*)(L)?")) != null)
                radix = 8;
            else if ((matcher = ctx.tryPattern("(?si)(\\+|-|)([1-9][0-9]*)(L)?")) != null)
                radix = 10;
            else
                throw new IllegalArgumentException("no pattern matched");
            final String digits = matcher.group(1) + matcher.group(2);
            final boolean isLong = matcher.group(3) != null;
            if (ctx.tryPattern("[.\\p{javaJavaIdentifierPart}]") != null)
                throw new IllegalArgumentException("followed by floating stuff");
            final BigInteger big = new BigInteger(digits, radix);
            if (big.bitLength() > (isLong ? 64 : 32))
                throw new IllegalArgumentException("bit length = " + big.bitLength());
            final Number value = isLong ? (Number)big.longValue() : (Number)big.intValue();
            return new LiteralNode(value);
        } catch (IllegalArgumentException e) {
            ctx.setIndex(start);
        }

        // Try to match float or double literal
        final Matcher floatMatch = ctx.tryPattern(
          "(" + Primitive.FLOAT.getParsePattern() + ")[fF](?!\\p{javaJavaIdentifierPart})");
        if (floatMatch != null) {
            try {
                return new LiteralNode(Primitive.FLOAT.parseValue(floatMatch.group(1)));
            } catch (IllegalArgumentException e) {
                ctx.setIndex(start);
            }
        }
        final Matcher doubleMatch = ctx.tryPattern(
          "(" + Primitive.DOUBLE.getParsePattern() + ")[dD]?(?![.\\p{javaJavaIdentifierPart}])");
        if (doubleMatch != null) {
            try {
                return new LiteralNode(Primitive.DOUBLE.parseValue(doubleMatch.group()));
            } catch (IllegalArgumentException e) {
                ctx.setIndex(start);
            }
        }

        // Try to match a char literal
        final Matcher charMatch = ctx.tryPattern(
          StringEncoder.ENQUOTE_PATTERN.toString().replaceAll("\"", "'").replaceAll("\\*", ""));    // kludge
        if (charMatch != null) {
            String match = charMatch.group();
            match = match.substring(1, match.length() - 1);
            if (match.length() > 0 && match.charAt(0) == '\'') {
                ctx.setIndex(start);
                throw new ParseException(ctx, "invalid character: contains unescaped single quote");
            }
            match = StringEncoder.decode(match.replaceAll(Pattern.quote("\\'"), Matcher.quoteReplacement("'")));
            if (match.length() != 1) {
                ctx.setIndex(start);
                throw new ParseException(ctx, "invalid character: quotes must contain exactly one character");
            }
            return new LiteralNode(match.charAt(0));
        }

        // Try to match a string literal
        final Matcher stringMatch = ctx.tryPattern(StringEncoder.ENQUOTE_PATTERN);
        if (stringMatch != null)
            return new LiteralNode(StringEncoder.dequote(stringMatch.group()));

        // Try to match variable
        if (ctx.tryLiteral("$")) {
            final Matcher varMatcher = ctx.tryPattern(ParseUtil.IDENT_PATTERN);
            if (varMatcher == null)
                throw new ParseException(ctx).addCompletions(session.getVars().keySet());
            final String name = varMatcher.group();
            if (ctx.isEOF() && complete)
                throw new ParseException(ctx).addCompletions(ParseUtil.complete(session.getVars().keySet(), name));
            return new VarNode(name);
        }

        // Try to match object literal
        if ((session.getMode().hasCoreAPI() || session.getMode().hasPermazen()) && ctx.tryLiteral("@")) {
            final ObjId id = new ObjIdParser().parse(session, ctx, complete);
            return !session.getMode().hasPermazen() ? new LiteralNode(id) : new Node() {
                @Override
                public Value evaluate(ParseSession session) {
                    return new ConstValue(JTransaction.getCurrent().get(id));
                }

                @Override
                public Class<?> getType(ParseSession session) {
                    try {
                        return session.getPermazen().getJClass(id).getType();
                    } catch (TypeNotInSchemaVersionException e) {
                        return UntypedJObject.class;
                    } catch (IllegalArgumentException e) {
                        return Object.class;
                    }
                }
            };
        }

        // No match
        throw new ParseException(ctx);
    }
}
