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
 * Derived from org.apache.jena.riot.lang.LangTriG of Apache Jena 6.2.0: the graph block grammar is the same, with the
 * message delimiter as an extra top level element.
 */
package org.openldes.jena.messages.lang;

import static org.apache.jena.riot.tokens.TokenType.DOT;
import static org.apache.jena.riot.tokens.TokenType.EQUALS;
import static org.apache.jena.riot.tokens.TokenType.KEYWORD;
import static org.apache.jena.riot.tokens.TokenType.LBRACE;
import static org.apache.jena.riot.tokens.TokenType.LBRACKET;
import static org.apache.jena.riot.tokens.TokenType.LPAREN;
import static org.apache.jena.riot.tokens.TokenType.LT2;
import static org.apache.jena.riot.tokens.TokenType.RBRACE;
import static org.apache.jena.riot.tokens.TokenType.RBRACKET;

import org.apache.jena.graph.Node;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.riot.system.ParserProfile;
import org.apache.jena.riot.tokens.Token;
import org.apache.jena.riot.tokens.Tokenizer;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.util.Context;

/** TriG with message delimiters: Jena's TriG parser, plus {@code MESSAGE} and {@code @message .} between graph blocks. */
public final class LangTriGMessages extends LangTurtleMessagesBase {

	public LangTriGMessages(Tokenizer tokens, ParserProfile profile, MessageFramer framer, Context context) {
		super(tokens, profile, framer, context);
	}

	@Override
	public Lang getLang() {
		return RDFLanguages.TRIG;
	}

	@Override
	protected void oneTopLevelElement() {
		if (possibleDelimiter()) {
			return;
		}
		oneNamedGraphBlock();
	}

	// RDF 1.1 TriG: the default graph can be Turtle (no braces), and the keyword GRAPH is optional.
	private void oneNamedGraphBlock() {
		Node graphNode = null;
		Token token = peekToken();
		Token t = token; // Keep for error messages
		boolean mustBeNamedGraph = false;

		if (lookingAt(KEYWORD)) {
			if (token.getImage().equalsIgnoreCase("GRAPH")) {
				nextToken();
				mustBeNamedGraph = true;
				token = peekToken();
			} else {
				exception(t, "Keyword '" + token.getImage() + "' not allowed here");
			}
		}

		if (lookingAt(LBRACKET)) {
			nextToken();
			token = peekToken();
			Node blank = profile.createBlankNode(graphNode, t.getLine(), t.getColumn());
			if (lookingAt(RBRACKET)) {
				// Can be Turtle, "[] :predicate", or named graph "[] {"
				nextToken();
				if (lookingAt(LBRACE)) {
					graphNode = blank;
				} else {
					if (mustBeNamedGraph) {
						exception(t, "Keyword 'GRAPH' must start a named graph");
					}
					turtle(blank);
					return;
				}
			} else {
				// [ :p ... ] and [ :p ... ] :p ...
				if (mustBeNamedGraph) {
					exception(t, "Keyword 'GRAPH' must start a named graph");
				}
				triplesBlankNode(blank);
				if (peekPredicate()) {
					predicateObjectList(blank);
				}
				expectEndOfTriplesTurtle();
				return;
			}
		} else if (token.isNode()) {
			// Either :s :p :o or :g { ... }
			Node n = node();
			token = peekToken();
			if (lookingAt(LBRACE)) {
				graphNode = n;
			} else {
				if (mustBeNamedGraph) {
					exception(t, "Keyword 'GRAPH' must start a named graph");
				}
				turtle(n);
				return;
			}
		} else if (lookingAt(LPAREN) || lookingAt(LT2)) {
			// A list, or a reified triple, as subject
			triples();
			return;
		}

		if (mustBeNamedGraph && graphNode == null) {
			exception(t, "Keyword 'GRAPH' must be followed by a graph name");
		}
		bracedGraph(t, graphNode);
	}

	private void turtle(Node n) {
		predicateObjectList(n);
		expectEndOfTriplesTurtle();
	}

	private void bracedGraph(Token t, Node graphNode) {
		if (graphNode != null) {
			if (graphNode.isURI() || graphNode.isBlank()) {
				setCurrentGraph(graphNode);
			} else {
				exception(t, "Not a legal graph name: " + graphNode);
			}
		} else {
			setCurrentGraph(Quad.tripleInQuad);
		}

		Token token = peekToken();
		// = is optional and old style.
		if (lookingAt(EQUALS)) {
			if (isStrictMode()) {
				exception(token, "Use of = {} is not part of standard TriG: " + graphNode);
			}
			nextToken();
			token = peekToken();
		}

		if (!lookingAt(LBRACE)) {
			exception(token, "Expected start of graph: got %s", peekToken());
		}
		nextToken();

		// Turtle, but no directives: a message delimiter in here is "Out of place", which is what the specification wants.
		while (true) {
			if (lookingAt(RBRACE)) {
				break;
			}
			triples();
		}

		token = nextToken();
		if (lookingAt(RBRACE)) {
			exception(token, "Expected end of graph: got %s", token);
		}

		if (!isStrictMode()) {
			// Skip DOT after {}
			if (lookingAt(DOT)) {
				nextToken();
			}
		}
		setCurrentGraph(Quad.tripleInQuad);
	}

	@Override
	protected void expectEndOfTriples() {
		// The DOT is required by Turtle (strictly). It is not required in TriG inside braces.
		if (lookingAt(DOT)) {
			nextToken();
			return;
		}
		if (lookingAt(RBRACE)) {
			// Don't consume the RBRACE - used to break the loop of triples blocks.
			return;
		}
		exception(peekToken(), "Triples not terminated properly: expected '.', '}' or EOF: got %s", peekToken());
	}

	@Override
	protected void emit(Node subject, Node predicate, Node object) {
		Node graph = getCurrentGraph();
		if (graph == Quad.tripleInQuad) {
			graph = Quad.defaultGraphNodeGenerated;
		}
		Quad quad = profile.createQuad(graph, subject, predicate, object, currLine, currCol);
		dest.quad(quad);
	}
}
