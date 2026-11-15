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
 * Derived from org.apache.jena.riot.lang.LangTurtle of Apache Jena 6.2.0.
 */
package org.openldes.jena.messages.lang;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.riot.system.ParserProfile;
import org.apache.jena.riot.tokens.Tokenizer;
import org.apache.jena.sparql.util.Context;

/** Turtle with message delimiters: Jena's Turtle parser, plus {@code MESSAGE} and {@code @message .} at the top level. */
public final class LangTurtleMessages extends LangTurtleMessagesBase {

	public LangTurtleMessages(Tokenizer tokens, ParserProfile profile, MessageFramer framer, Context context) {
		super(tokens, profile, framer, context);
		setCurrentGraph(null);
	}

	@Override
	public Lang getLang() {
		return RDFLanguages.TURTLE;
	}

	@Override
	protected void oneTopLevelElement() {
		if (possibleDelimiter()) {
			return;
		}
		triples();
	}

	@Override
	protected void expectEndOfTriples() {
		expectEndOfTriplesTurtle();
	}

	@Override
	protected void emit(Node subject, Node predicate, Node object) {
		Triple t = profile.createTriple(subject, predicate, object, currLine, currCol);
		dest.triple(t);
	}
}
