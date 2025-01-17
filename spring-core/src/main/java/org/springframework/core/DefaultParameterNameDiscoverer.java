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

package org.springframework.core;

/**
 * Default implementation of the {@link ParameterNameDiscoverer} strategy interface,
 * delegating to the Java 8 standard reflection mechanism, with a deprecated fallback
 * to {@link LocalVariableTableParameterNameDiscoverer}.
 *
 * <p>{@link ParameterNameDiscoverer}策略接口的默认实现，使用Java 8标准反射机制(如果有),
 * 然后回退基于ASM的{@link LocalVariableTableParameterNameDiscoverer},以检查在类文件中
 * 调试信息。
 *
 * <p>If a Kotlin reflection implementation is present,
 * {@link KotlinReflectionParameterNameDiscoverer} is added first in the list and
 * used for Kotlin classes and interfaces.
 *
 * <p>
 *  如果存在Kotlin反射实现，则将{@link KotlinReflectionParameterNameDiscoverer}首先添加
 *  到列表中,并将用于Kotlin类和接口。当编译或作为Graal本机映像时,不使用{@link ParameterNameDiscoverer}
 *
 * <p>Further discoverers may be added through {@link #addDiscoverer(ParameterNameDiscoverer)}.
 * <p>可以通过{@link #addDiscoverer(ParameterNameDiscoverer)}添加更多发现器</p>
 *
 * @author Juergen Hoeller
 * @author Sebastien Deleuze
 * @author Sam Brannen
 * @since 4.0
 * @see StandardReflectionParameterNameDiscoverer
 * @see KotlinReflectionParameterNameDiscoverer
 */
public class DefaultParameterNameDiscoverer extends PrioritizedParameterNameDiscoverer {

	@SuppressWarnings("removal")
	public DefaultParameterNameDiscoverer() {
		//如果存在Kotlin反射
		if (KotlinDetector.isKotlinReflectPresent()) {
			//添加Kotlin的反射工具内省参数名发现器
			addDiscoverer(new KotlinReflectionParameterNameDiscoverer());
		}

		// Recommended approach on Java 8+: compilation with -parameters.
		//添加 使用JDK8的反射工具内省参数名 (基于'-parameters'编译器标记)的参数名发现器
		addDiscoverer(new StandardReflectionParameterNameDiscoverer());

		// Deprecated fallback to class file parsing for -debug symbols.
		// Does not work on native images without class file resources.
		//添加 基于ASM库对Class文件的解析获取LocalVariableTable信息来发现参数名 的参数名发现器
		if (!NativeDetector.inNativeImage()) {
			addDiscoverer(new LocalVariableTableParameterNameDiscoverer());
		}
	}

}
