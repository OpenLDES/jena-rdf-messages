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
 * Derived from org.apache.jena.riot.lang.LangNQuads of Apache Jena 6.2.0.
 */
package org.openldes.jena.messages.lang;

import org.apache.jena.graph.Node;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.riot.lang.LangNTuple;
import org.apache.jena.riot.system.ParserProfile;
import org.apache.jena.riot.tokens.Token;
import org.apache.jena.riot.tokens.TokenType;
import org.apache.jena.riot.tokens.Tokenizer;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.util.Context;

/**
 * N-Quads with message delimiters: Jena's N-Quads parser, plus the {@code MESSAGE} directive, which must be on a line of
 * its own as the grammar of N-Quads is line-based.
 */
public final class LangNQuadsMessages extends LangNTuple<Quad> {

	private final MessageFramer framer;
	// Null for no graph.
	private Node currentGraph = null;

	public LangNQuadsMessages(Tokenizer tokens, ParserProfile profile, MessageFramer framer, Context context) {
		super(tokens, profile, framer, context);
		this.framer = framer;
	}

	@Override
	public Lang getLang() {
		return RDFLanguages.NQUADS;
	}

	@Override
	protected void runParser() {
		while (hasNext()) {
			Quad quad = parseOneStatement();
			if (quad != null) {
				dest.quad(quad);
			}
		}
	}

	@Override
	protected Quad parseOneStatement() {
		if (LangNTupleMessages.possibleDelimiter(this, framer)) {
			return null;
		}
		if (parsePossibleVersion()) {
			return null;
		}
		return parseQuad();
	}

	private Quad parseQuad() {
		Token token = peekToken();
		Node s = parseSubject();
		Node p = parsePredicate();
		Node o = parseObject();

		Token xToken = nextToken(); // Maybe DOT
		if (xToken.getType() == TokenType.EOF) {
			exception(xToken, "Premature end of file: Quad not terminated by DOT: %s", xToken);
		}

		// Process graph node first, before S,P,O to set bnode label scope (if not global)
		Node c;
		if (xToken.getType() != TokenType.DOT) {
			// Allow bNodes for graph names.
			checkIRIOrBNode(xToken);
			c = tokenAsNode(xToken);
			xToken = nextToken();
			currentGraph = c;
		} else {
			c = Quad.defaultGraphNodeGenerated;
			currentGraph = null;
		}

		if (xToken.getType() != TokenType.DOT) {
			exception(xToken, "Quad not terminated by DOT: %s", xToken);
		}
		return profile.createQuad(c, s, p, o, token.getLine(), token.getColumn());
	}

	@Override
	protected Node tokenAsNode(Token token) {
		return profile.create(currentGraph, token);
	}

	// Accessors for the shared delimiter logic
	Token peekTokenForDelimiter() {
		return peekToken();
	}

	Token nextTokenForDelimiter() {
		return nextToken();
	}

	long currentLine() {
		return currLine;
	}

	void delimiterError(Token token, String message) {
		exception(token, message);
	}
}
