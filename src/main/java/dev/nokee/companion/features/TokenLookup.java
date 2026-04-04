/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.nokee.companion.features;

import org.gradle.language.nativeplatform.internal.Expression;

import java.util.*;

/**
 * Captures the intermediate states during the resolution of macro #include directives, to short-circuit work that has already been done.
 */
class TokenLookup {
	private Set<Expression> broken;
	private Map<Expression, Set<Expression>> tokensFor;

	boolean isUnresolved(Expression expression) {
		return broken != null && broken.contains(expression);
	}

	void unresolved(Expression expression) {
		if (broken == null) {
			broken = new HashSet<Expression>();
		}
		broken.add(expression);
	}

	Collection<Expression> tokensFor(Expression expression) {
		if (tokensFor == null) {
			return Collections.emptyList();
		}
		return tokensFor.get(expression);
	}

	void addTokensFor(Expression expression, Expression tokens) {
		if (tokensFor == null) {
			tokensFor = new LinkedHashMap<>();
		}
		tokensFor.computeIfAbsent(expression, __ -> new LinkedHashSet<>()).add(tokens);
	}

	boolean hasTokensFor(Expression expression) {
		return tokensFor != null && tokensFor.containsKey(expression);
	}
}
