/*
 * Copyright 2002-2022 the original author or authors.
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

import org.springframework.lang.Nullable;
import org.springframework.util.PatternMatchUtils;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pointcut bean for simple method name matches, as an alternative to regexp patterns.
 *
 * <p>Does not handle overloaded methods: all methods with a given name will be eligible.
 *
 * @author Juergen Hoeller
 * @author Rod Johnson
 * @author Rob Harrop
 * @since 11.02.2004
 * @see #isMatch
 */
@SuppressWarnings("serial")
public class NameMatchMethodPointcut extends StaticMethodMatcherPointcut implements Serializable {

	// 匹配模板
	private List<String> mappedNames = new ArrayList<>();


	/**
	 * Convenience method when we have only a single method name to match.
	 * Use either this method or {@code setMappedNames}, not both.
	 * @see #setMappedNames
	 */
	public void setMappedName(String mappedName) {
		setMappedNames(mappedName);
	}

	/**
	 * Set the method names defining methods to match.
	 * Matching will be the union of all these; if any match,
	 * the pointcut matches.
	 */
	public void setMappedNames(String... mappedNames) {
		this.mappedNames = new ArrayList<>(Arrays.asList(mappedNames));
	}

	/**
	 * Add another eligible method name, in addition to those already named.
	 * Like the set methods, this method is for use when configuring proxies,
	 * before a proxy is used.
	 * 添加模板方法
	 * <p><b>NB:</b> This method does not work after the proxy is in
	 * use, as advice chains will be cached.
	 * @param name the name of the additional method that will match
	 * @return this pointcut to allow for multiple additions in one line
	 */
	public NameMatchMethodPointcut addMethodName(String name) {
		this.mappedNames.add(name);
		return this;
	}


	/**
	 * 静态匹配，equals 或者正则匹配
	 * @param method the candidate method
	 * @param targetClass the target class
	 * @return
	 */
	@Override
	public boolean matches(Method method, Class<?> targetClass) {
		for (String mappedName : this.mappedNames) {
			if (mappedName.equals(method.getName()) || isMatch(method.getName(), mappedName)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Return if the given method name matches the mapped name.
	 * <p>The default implementation checks for "xxx*", "*xxx" and "*xxx*" matches,
	 * as well as direct equality. Can be overridden in subclasses.
	 * <p>支持形如 "xxx*", "*xxx" 的正则匹配</p>
	 * @param methodName the method name of the class
	 * @param mappedName the name in the descriptor
	 * @return if the names match
	 * @see org.springframework.util.PatternMatchUtils#simpleMatch(String, String)
	 */
	protected boolean isMatch(String methodName, String mappedName) {
		return PatternMatchUtils.simpleMatch(mappedName, methodName);
	}


	@Override
	public boolean equals(@Nullable Object obj) {
		return (this == obj || (obj instanceof NameMatchMethodPointcut that &&
				this.mappedNames.equals(that.mappedNames)));
	}

	@Override
	public int hashCode() {
		return this.mappedNames.hashCode();
	}

	@Override
	public String toString() {
		return getClass().getName() + ": " + this.mappedNames;
	}

}
