/*
 * Copyright 2002-2021 the original author or authors.
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

package org.springframework.util;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.lang.Nullable;

/**
 * Utility class for working with Strings that have placeholder values in them.
 * A placeholder takes the form {@code ${name}}. Using {@code PropertyPlaceholderHelper}
 * these placeholders can be substituted for user-supplied values.
 *
 * <p>Values for substitution can be supplied using a {@link Properties} instance or
 * using a {@link PlaceholderResolver}.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @since 3.0
 */
public class PropertyPlaceholderHelper {

	private static final Log logger = LogFactory.getLog(PropertyPlaceholderHelper.class);

	private static final Map<String, String> wellKnownSimplePrefixes = new HashMap<>(4);

	static {
		wellKnownSimplePrefixes.put("}", "{");
		wellKnownSimplePrefixes.put("]", "[");
		wellKnownSimplePrefixes.put(")", "(");
	}


	/**
	 * 占位符前缀，默认为"${"
	 */
	private final String placeholderPrefix;

	/**
	 * 占位符后缀，默认为"}"
	 */
	private final String placeholderSuffix;

	/**
	 * 简单的占位符前缀，默认为"{"，嵌套的占位符中有效比如${xx{yy}}
	 */
	private final String simplePrefix;

	/**
	 * 占位符变量和默认值的分隔符，默认为":"
	 */
	@Nullable
	private final String valueSeparator;

	/**
	 * 是否忽略无法解析的占位符，默认false，将会抛出异常，如果为true，那么跳过这个占位符
	 */
	private final boolean ignoreUnresolvablePlaceholders;


	/**
	 * Creates a new {@code PropertyPlaceholderHelper} that uses the supplied prefix and suffix.
	 * Unresolvable placeholders are ignored.
	 * @param placeholderPrefix the prefix that denotes the start of a placeholder
	 * @param placeholderSuffix the suffix that denotes the end of a placeholder
	 */
	public PropertyPlaceholderHelper(String placeholderPrefix, String placeholderSuffix) {
		this(placeholderPrefix, placeholderSuffix, null, true);
	}

	/**
	 * Creates a new {@code PropertyPlaceholderHelper} that uses the supplied prefix and suffix.
	 * @param placeholderPrefix the prefix that denotes the start of a placeholder
	 * @param placeholderSuffix the suffix that denotes the end of a placeholder
	 * @param valueSeparator the separating character between the placeholder variable
	 * and the associated default value, if any
	 * @param ignoreUnresolvablePlaceholders indicates whether unresolvable placeholders should
	 * be ignored ({@code true}) or cause an exception ({@code false})
	 */
	public PropertyPlaceholderHelper(String placeholderPrefix, String placeholderSuffix,
			@Nullable String valueSeparator, boolean ignoreUnresolvablePlaceholders) {

		Assert.notNull(placeholderPrefix, "'placeholderPrefix' must not be null");
		Assert.notNull(placeholderSuffix, "'placeholderSuffix' must not be null");
		this.placeholderPrefix = placeholderPrefix;
		this.placeholderSuffix = placeholderSuffix;
		String simplePrefixForSuffix = wellKnownSimplePrefixes.get(this.placeholderSuffix);
		if (simplePrefixForSuffix != null && this.placeholderPrefix.endsWith(simplePrefixForSuffix)) {
			this.simplePrefix = simplePrefixForSuffix;
		}
		else {
			this.simplePrefix = this.placeholderPrefix;
		}
		this.valueSeparator = valueSeparator;
		this.ignoreUnresolvablePlaceholders = ignoreUnresolvablePlaceholders;
	}


	/**
	 * Replaces all placeholders of format {@code ${name}} with the corresponding
	 * property from the supplied {@link Properties}.
	 * @param value the value containing the placeholders to be replaced
	 * @param properties the {@code Properties} to use for replacement
	 * @return the supplied value with placeholders replaced inline
	 */
	public String replacePlaceholders(String value, final Properties properties) {
		Assert.notNull(properties, "'properties' must not be null");
		return replacePlaceholders(value, properties::getProperty);
	}

	/**
	 * Replaces all placeholders of format {@code ${name}} with the value returned
	 * from the supplied {@link PlaceholderResolver}.
	 * 替换所有的占位符为对应的值
	 * @param value the value containing the placeholders to be replaced
	 * 包含要替换的占位符的值
	 * @param placeholderResolver the {@code PlaceholderResolver} to use for replacement
	 * 占位符解析器
	 * @return the supplied value with placeholders replaced inline
	 * 替换之后的值
	 */
	public String replacePlaceholders(String value, PlaceholderResolver placeholderResolver) {
		//value不能为null
		Assert.notNull(value, "'value' must not be null");
		//继续调用parseStringValue方法
		return parseStringValue(value, placeholderResolver, null);
	}

	/**
	 * 最终调用的方法，递归的解析字符串中的占位符
	 * @param value  包含要替换的占位符的值
	 * @param placeholderResolver 占位符解析器
	 * @param visitedPlaceholders 访问过的占位符，用于递归向后推进，同时避免递归解析导致的死循环
	 * @return 替换之后的值
	 */
	protected String parseStringValue(
			String value, PlaceholderResolver placeholderResolver, @Nullable Set<String> visitedPlaceholders) {

		//获取第一个占位符前缀索引值startIndex
		int startIndex = value.indexOf(this.placeholderPrefix);
		//如果startIndex为-1，表示没有占位符，不需要继续解析，直接返回原值
		if (startIndex == -1) {
			return value;
		}
		//到这里，表示存在占位符，开始解析
		//先创建一个StringBuilder，传入value
		StringBuilder result = new StringBuilder(value);
		/*如果startIndex不为-1，那么一直循环，直到将全部占位符都解析完毕或者抛出异常*/
		while (startIndex != -1) {
			//获取对应占位符结束位置索引
			int endIndex = findPlaceholderEndIndex(result, startIndex);
			if (endIndex != -1) {
				//获取开始索引和结束索引之间的占位符变量
				String placeholder = result.substring(startIndex + this.placeholderPrefix.length(), endIndex);
				String originalPlaceholder = placeholder;
				//添加到已解析占位符集合中
				if (visitedPlaceholders == null) {
					visitedPlaceholders = new HashSet<>(4);
				}
				//如果没有添加成功，这说明出现了同名的的嵌套占位符，这类似于循环引用，那么抛出异常
				//主要出现在value的递归解析中，后面会有案例
				if (!visitedPlaceholders.add(originalPlaceholder)) {
					throw new IllegalArgumentException(
							"Circular placeholder reference '" + originalPlaceholder + "' in property definitions");
				}
				//递归调用parseStringValue，用于分析占位符中的占位符……，这里是用于分析key
				// Recursive invocation, parsing placeholders contained in the placeholder key.
				placeholder = parseStringValue(placeholder, placeholderResolver, visitedPlaceholders);
				//到这里表示递归完毕，占位符中没有占位符了
				//找到最底层的占位符变量之后，调用placeholderResolver的resolvePlaceholder(placeholder)方法，根据lambda表达式
				//实际上就是调用PropertySourcesPropertyResolver对象的getPropertyAsRawString方法，然后又会调用
				//PropertySourcesPropertyResolver.getProperty方法中通过占位符变量找出对应的值
				String propVal = placeholderResolver.resolvePlaceholder(placeholder);
				//如果没找到（值为null），并且默认值分隔符不为null，那么尝试获取默认值
				if (propVal == null && this.valueSeparator != null) {
					//分隔符的起始索引
					int separatorIndex = placeholder.indexOf(this.valueSeparator);
					if (separatorIndex != -1) {
						//实际的占位符变量
						String actualPlaceholder = placeholder.substring(0, separatorIndex);
						//默认值
						String defaultValue = placeholder.substring(separatorIndex + this.valueSeparator.length());
						//尝试从属性源中查找占位符变量对应的属性值
						propVal = placeholderResolver.resolvePlaceholder(actualPlaceholder);
						//如果没找到，那么就使用默认值了
						if (propVal == null) {
							propVal = defaultValue;
						}
					}
				}
				//到这一步，如果值不为null，说明解析到了值，无论是默认值还是属性值
				if (propVal != null) {
					//继续递归调用parseStringValue，用于分析占位符的值中的占位符……，这里是用于分析value
					// Recursive invocation, parsing placeholders contained in the
					// previously resolved placeholder value.
					propVal = parseStringValue(propVal, placeholderResolver, visitedPlaceholders);
					//到这里表示递归完毕，占位符的值中没有占位符了
					//这里将占位符替换成获取到的值
					result.replace(startIndex, endIndex + this.placeholderSuffix.length(), propVal);
					if (logger.isTraceEnabled()) {
						logger.trace("Resolved placeholder '" + placeholder + "'");
					}
					//获取下一个占位符的起始索引
					startIndex = result.indexOf(this.placeholderPrefix, startIndex + propVal.length());
				}
				//如果值为null，并且默认值分隔符为null，这就是解析失败的情况，判断是不是忽略，如果是的话，那么解析下一个占位符，同时将当前占位符整体作为值
				else if (this.ignoreUnresolvablePlaceholders) {
					// Proceed with unprocessed value.
					startIndex = result.indexOf(this.placeholderPrefix, endIndex + this.placeholderSuffix.length());
				}
				//如果不能忽略，那么抛出异常
				else {
					throw new IllegalArgumentException("Could not resolve placeholder '" +
							placeholder + "'" + " in value \"" + value + "\"");
				}
				//移除已解析的占位符变量
				visitedPlaceholders.remove(originalPlaceholder);
			} else {
				//出现问题，没找到后缀，下一次循环直接退出
				startIndex = -1;
			}
		}
		//返回解析后的结果
		return result.toString();
	}

	/**
	 * 获取占位符的结束下标
	 * @param buf 输入文本
	 * @param startIndex 开始位置
	 * @return
	 */
	private int findPlaceholderEndIndex(CharSequence buf, int startIndex) {
		//首先查找的下标要跳过占位符前缀，所以从占位符下标+占位符长度开始查询
		int index = startIndex + this.placeholderPrefix.length();
		//内嵌占位符个数。内嵌占位符类似于${abc${def}}
		int withinNestedPlaceholder = 0;
		while (index < buf.length()) {
			//判断字符串index处是否是占位符后缀
			if (StringUtils.substringMatch(buf, index, this.placeholderSuffix)) {
				//如果是，判断内嵌占位符数量是否大于0
				if (withinNestedPlaceholder > 0) {
					//内嵌占位符数量大于0，则withinNestedPlaceholder减一，然后继续查询下一个后缀
					withinNestedPlaceholder--;
					index = index + this.placeholderSuffix.length();
				} else {
					//没有内嵌占位符，则当前下标为占位符后缀下标，返回
					return index;
				}
			}
			//判断index处是否是simplePrefix，此处对simplePrefix做个说明。如果前缀以{,[或(结尾，则simplePrefix为{,[或(。如果不是，则为占位符前缀
			else if (StringUtils.substringMatch(buf, index, this.simplePrefix)) {
				//如果index处为simplePrefix。则说明有内嵌占位符，withinNestedPlaceholder加一，继续往后查找
				withinNestedPlaceholder++;
				index = index + this.simplePrefix.length();
			} else {
				//如果index处既不是simplePrefix也不是占位符后缀，index加一继续查找
				index++;
			}
		}
		//没有查到，返回-1
		return -1;
	}


	/**
	 * Strategy interface used to resolve replacement values for placeholders contained in Strings.
	 */
	@FunctionalInterface
	public interface PlaceholderResolver {

		/**
		 * Resolve the supplied placeholder name to the replacement value.
		 * 将提供的占位符名称解析为替换值
		 * @param placeholderName the name of the placeholder to resolve
		 * @return the replacement value, or {@code null} if no replacement is to be made
		 */
		@Nullable
		String resolvePlaceholder(String placeholderName);
	}

}
