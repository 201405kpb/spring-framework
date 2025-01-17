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

package org.springframework.core.env;

import org.springframework.lang.Nullable;

/**
 * {@link PropertyResolver} implementation that resolves property values against
 * an underlying set of {@link PropertySources}.
 *
 * 继承AbstractPropertyResolver抽象类，作为非web环境下的PropertyResolver体系的默认实现也是唯一实现也是，
 * 它将PropertySources（类型为PropertySource）属性源集合作为属性来源，通过顺序遍历每一个PropertySource属性源，
 * 返回第一个找到（不为null）的属性key对应的属性value
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @since 3.1
 * @see PropertySource
 * @see PropertySources
 * @see AbstractEnvironment
 */
public class PropertySourcesPropertyResolver extends AbstractPropertyResolver {

	@Nullable
	private final PropertySources propertySources;


	/**
	 * Create a new resolver against the given property sources.
	 * @param propertySources the set of {@link PropertySource} objects to use
	 */
	public PropertySourcesPropertyResolver(@Nullable PropertySources propertySources) {
		this.propertySources = propertySources;
	}


	@Override
	public boolean containsProperty(String key) {
		if (this.propertySources != null) {
			for (PropertySource<?> propertySource : this.propertySources) {
				if (propertySource.containsProperty(key)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * 将指定的属性检索为原始字符串，即不解析嵌套占位符
	 * @param key the property name to resolve 占位符
	 * @return 解析结果
	 */
	@Override
	@Nullable
	public String getProperty(String key) {
		return getProperty(key, String.class, true);
	}

	@Override
	@Nullable
	public <T> T getProperty(String key, Class<T> targetValueType) {
		return getProperty(key, targetValueType, true);
	}

	/**
	 * 将指定的属性检索为原始字符串，即不解析嵌套占位符。
	 * @param key the property name to resolve 占位符变量
	 * @return 解析结果
	 */
	@Override
	@Nullable
	protected String getPropertyAsRawString(String key) {
		//调用getProperty方法
		return getProperty(key, String.class, false);
	}

	/**
	 * 通过key获取属性值
	 *
	 * @param key                       占位符变量，即属性key
	 * @param targetValueType           返回值类型
	 * @param resolveNestedPlaceholders 是否解析嵌套占位符
	 * @return 解析后的值
	 */
	@Nullable
	protected <T> T getProperty(String key, Class<T> targetValueType, boolean resolveNestedPlaceholders) {
		//如果属性源不为null，在调用getEnvironment方法获取环境对象的时候propertySources就被初始化了，肯定是不为null的
		//并且还通过customizePropertySources方法被设置了系统(systemEnvironment)和JVM(systemProperties)属性源
		if (this.propertySources != null) {
			//propertySources实现了Iterable，是一个可迭代的对象，相当于一个列表
			//每一个列表元素代表一个属性源，这个属性源就相当于一个map，存放的是属性键值对
			for (PropertySource<?> propertySource : this.propertySources) {
				if (logger.isTraceEnabled()) {
					logger.trace("Searching for key '" + key + "' in PropertySource '" +
							propertySource.getName() + "'");
				}
				//获取属性源里key对应的value
				Object value = propertySource.getProperty(key);
				//选用第一个不为null的匹配key的属性值
				if (value != null) {
					//如果需要递归解析value中的嵌套占位符比如${}，并且value属于String类型
					if (resolveNestedPlaceholders && value instanceof String) {
						//那么递归解析
						value = resolveNestedPlaceholders((String) value);
					}
					//记录日志
					logKeyFound(key, propertySource, value);
					//调用父类AbstractPropertyResolver中的方法：如有必要，将给定值转换为指定的目标类型并返回
					return convertValueIfNecessary(value, targetValueType);
				}
			}
		}
		//记录日志
		if (logger.isTraceEnabled()) {
			logger.trace("Could not find key '" + key + "' in any property source");
		}
		//propertySources为null，那么直接返回null，因为没有任何属性源
		return null;
	}

	/**
	 * Log the given key as found in the given {@link PropertySource}, resulting in
	 * the given value.
	 * <p>The default implementation writes a debug log message with key and source.
	 * As of 4.3.3, this does not log the value anymore in order to avoid accidental
	 * logging of sensitive settings. Subclasses may override this method to change
	 * the log level and/or log message, including the property's value if desired.
	 * @param key the key found
	 * @param propertySource the {@code PropertySource} that the key has been found in
	 * @param value the corresponding value
	 * @since 4.3.1
	 */
	protected void logKeyFound(String key, PropertySource<?> propertySource, Object value) {
		if (logger.isDebugEnabled()) {
			logger.debug("Found key '" + key + "' in PropertySource '" + propertySource.getName() +
					"' with value of type " + value.getClass().getSimpleName());
		}
	}

}
