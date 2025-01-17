/*
 * Copyright 2002-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.aop.support;

import org.springframework.aop.MethodMatcher;

import java.lang.reflect.Method;

/**
 * Convenient abstract superclass for static method matchers, which don't care
 * about arguments at runtime.
 *
 * 静态方法匹配器的方便抽象超类，它不关心运行时的参数。
 *
 * @author Rod Johnson
 */
public abstract class StaticMethodMatcher implements MethodMatcher {

	@Override
	public final boolean isRuntime() {
		// 因为是静态匹配，都是返回false
		return false;
	}

	@Override
	public final boolean matches(Method method, Class<?> targetClass, Object... args) {
		// should never be invoked because isRuntime() returns false
		// 静态匹配的 MethodMatcher，不应该调用这个动态的方法
		throw new UnsupportedOperationException("Illegal MethodMatcher usage");
	}

}
