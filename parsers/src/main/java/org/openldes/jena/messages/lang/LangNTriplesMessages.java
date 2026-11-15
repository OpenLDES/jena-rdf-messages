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
 * Derived from org.apache.jena.riot.lang.LangNTriples of Apache Jena 6.2.0.
 */
package org.openldes.jena.messages.lang;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.riot.lang.LangNTuple;
import org.apache.jena.riot.system.ParserProfile;
import org.apache.jena.riot.tokens.Token;
import org.apache.jena.riot.tokens.TokenType;
import org.apache.jena.riot.tokens.Tokenizer;
import org.apache.jena.sparql.util.Context;

/**
 * N-Triples with message delimiters: Jena's N-Triples parser, plus the {@code MESSAGE} directive, which must be on a line
 * of its own as the grammar of N-Triples is line-based.
 */
public final class LangNTriplesMessages extends LangNTuple<Triple> {

	private final MessageFramer framer;

	public LangNTriplesMessages(Tokenizer tokens, ParserProfile profile, MessageFramer framer, Context context) {
		super(tokens, profile, framer, context);
		this.framer = framer;
	}

	@Override
	public Lang getLang() {
		return RDFLanguages.NTRIPLES;
	}

	@Override
	protected void runParser() {
		while (hasNext()) {
			Triple triple = parseOneStatement();
			if (triple != null) {
				dest.triple(triple);
			}
		}
	}

	@Override
	protected Triple parseOneStatement() {
		if (LangNTupleMessages.possibleDelimiter(this, framer)) {
			return null;
		}
		if (parsePossibleVersion()) {
			return null;
		}
		Triple triple = parseTriple();
		Token x = nextToken();
		if (x.getType() != TokenType.DOT) {
			exception(x, "Triple not terminated by DOT: %s", x);
		}
		return triple;
	}

	@Override
	protected Node tokenAsNode(Token token) {
		return profile.create(null, token);
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
