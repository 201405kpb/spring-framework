/*
 * Copyright 2002-2013 the original author or authors.
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

package org.springframework.beans;

/**
 * <p>不在特定目标对象上运行的TypeConverter接口的简单实现。这是使用完整的BeanWrapperImpl实例来实现
 * 任意类型转换需求的替代方法，同时使用相同的转换算法（包括委托给PropertyEditor和ConversionService）。</p>
 * Simple implementation of the {@link TypeConverter} interface that does not operate on
 * a specific target object. This is an alternative to using a full-blown BeanWrapperImpl
 * instance for arbitrary type conversion needs, while using the very same conversion
 * algorithm (including delegation to {@link java.beans.PropertyEditor} and
 * {@link org.springframework.core.convert.ConversionService}) underneath.
 * <p>TypeConverter接口的简单实现,该接口不对特定目标对象进行操作.这是在底层使用完全相同的转换算法(
 * 包括对java.beans.PropertyEditor和org.springframework.core.convert.ConversionService的委派)
 * 使用完全成熟的BeanWrapperImpl实例以满足任意类型转换需求的替代方法</p>
 * <p><b>Note:</b> Due to its reliance on {@link java.beans.PropertyEditor PropertyEditors},
 * SimpleTypeConverter is <em>not</em> thread-safe. Use a separate instance for each thread.
 * <p>注意：由于依赖于PropertyEditors,因此SimpleTypeConverter不是线程安全的.为每个线程使用一个
 * 单独的实例</p>
 * @author Juergen Hoeller
 * @since 2.0
 * @see BeanWrapperImpl
 */
public class SimpleTypeConverter extends TypeConverterSupport {

	public SimpleTypeConverter() {
		this.typeConverterDelegate = new TypeConverterDelegate(this);
		registerDefaultEditors();
	}

}
