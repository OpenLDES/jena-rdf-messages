/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 *   SPDX-License-Identifier: Apache-2.0
 *
 * Derived from org.apache.jena.riot.lang.LangTurtle and LangTriG of Apache Jena 6.2.0.
 */
package org.openldes.jena.messages.lang;

import static org.apache.jena.riot.tokens.TokenType.DOT;
import static org.apache.jena.riot.tokens.TokenType.KEYWORD;

import org.apache.jena.riot.lang.LangTurtleBase;
import org.apache.jena.riot.system.ParserProfile;
import org.apache.jena.riot.tokens.Token;
import org.apache.jena.riot.tokens.Tokenizer;
import org.apache.jena.sparql.util.Context;

/**
 * The part of the Turtle and TriG message log parsers that recognizes a message delimiter at the top level of the
 * document: {@code MESSAGE}, or {@code @message .} (which the {@link MessageTokenizer} hands over as a keyword).
 * <p>
 * {@code LangTurtleBase} handles {@code PREFIX}, {@code BASE} and {@code VERSION} in its (final) main loop, and leaves
 * every other top level element to {@code oneTopLevelElement()}, which is where the delimiter is handled. A delimiter
 * anywhere else (in a graph block, a statement, a list, ...) is a syntax error of the grammar of Jena itself.
 */
abstract class LangTurtleMessagesBase extends LangTurtleBase {

	protected final MessageFramer framer;

	protected LangTurtleMessagesBase(Tokenizer tokens, ParserProfile profile, MessageFramer framer, Context context) {
		super(new MessageTokenizer(tokens), profile, framer, context);
		this.framer = framer;
	}

	/** Consumes a delimiter when the next token is one. Returns false when it is not. */
	protected final boolean possibleDelimiter() {
		if (!lookingAt(KEYWORD)) {
			return false;
		}
		Token token = peekToken();
		if (!MessageTokenizer.isDelimiter(token)) {
			return false;
		}
		nextToken();
		if (MessageTokenizer.isAtMessage(token)) {
			expect("@message directive not terminated by a dot", DOT);
		}
		framer.delimiter(token.getLine(), token.getColumn());
		return true;
	}
}
