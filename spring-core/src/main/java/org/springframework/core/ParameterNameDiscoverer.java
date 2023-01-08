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

package org.springframework.core;

import org.springframework.lang.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Interface to discover parameter names for methods and constructors.
 * <p>用于发现方法和构造函数的参数名的接口</p>
 *
 * <p>Parameter name discovery is not always possible, but various strategies are
 * available to try, such as looking for debug information that may have been
 * emitted at compile time, and looking for arg name annotation values optionally
 * accompanying AspectJ annotated methods.
 * <p>
 *     参数名并非总是可以发现参数名称，但可以尝试各种策略，比如寻找在编译时可能
 *     发生调试信号，和寻找可选的附带AspectJ注解方法的arg name注解值
 * </p>
 *
 * @author Rod Johnson
 * @author Adrian Colyer
 * @since 2.0
 */
public interface ParameterNameDiscoverer {

	/**
	 * Return parameter names for a method, or {@code null} if they cannot be determined.
	 * <p>
	 *     返回方法的参数名，如果不能确定就返回{@code null}
	 * </p>
	 * <p>Individual entries in the array may be {@code null} if parameter names are only
	 * available for some parameters of the given method but not for others. However,
	 * it is recommended to use stub parameter names instead wherever feasible.
	 * <p>
	 *     如果参数名称仅可用于给定方法的某些参数，而不适用于其他参数，则数组的各个条目
	 *     可能为{@code null}.但是，建议在可行的地方使用存根参数名名代替。
	 * </p>
	 * @param method the method to find parameter names for
	 *               -- 查找参数名称的方法
	 * @return an array of parameter names if the names can be resolved,
	 * or {@code null} if they cannot
	 * 			-- 如果名称能被解析就返回一组参数名，否则返回{@code null}
	 */
	@Nullable
	String[] getParameterNames(Method method);

	/**
	 * Return parameter names for a constructor, or {@code null} if they cannot be determined.
	 * <p>
	 *     返回构造函数的参数名，如果不能确定就返回{@code null}
	 * </p>
	 * <p>Individual entries in the array may be {@code null} if parameter names are only
	 * available for some parameters of the given constructor but not for others. However,
	 * it is recommended to use stub parameter names instead wherever feasible.
	 * <p>
	 *     如果参数名称仅可用于给定方法的某些参数，而不适用于其他参数，则数组的各个条目
	 *     可能为{@code null}.但是，建议在可行的地方使用存根参数名名代替。
	 * </p>
	 * @param ctor the constructor to find parameter names for
	 *             -- 查找参数名称的方法
	 * @return an array of parameter names if the names can be resolved,
	 * or {@code null} if they cannot
	 * 				-- 如果名称能被解析就返回一组参数名，否则返回{@code null}
	 */
	@Nullable
	String[] getParameterNames(Constructor<?> ctor);

}
