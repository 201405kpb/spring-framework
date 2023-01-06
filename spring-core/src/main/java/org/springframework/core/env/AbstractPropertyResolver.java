/*
 * Copyright 2002-2020 the original author or authors.
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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.ConfigurableConversionService;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.PropertyPlaceholderHelper;
import org.springframework.util.SystemPropertyUtils;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Abstract base class for resolving properties against any underlying source.
 * ConfigurablePropertyResolver接口的抽象实现类，实现了ConfigurablePropertyResolver接口的所有抽象方法，
 * 是用于针对任何基础属性源解析属性的抽象基类，定义了默认占位符属性格式“${…:…}”，以及其他比如转换服务属性、必备属性
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @since 3.1
 */
public abstract class AbstractPropertyResolver implements ConfigurablePropertyResolver {

	protected final Log logger = LogFactory.getLog(getClass());

	@Nullable
	private volatile ConfigurableConversionService conversionService;

	@Nullable
	private PropertyPlaceholderHelper nonStrictHelper;

	@Nullable
	private PropertyPlaceholderHelper strictHelper;

	private boolean ignoreUnresolvableNestedPlaceholders = false;

	/**
	 * 占位符开头的前缀
	 */
	private String placeholderPrefix = SystemPropertyUtils.PLACEHOLDER_PREFIX;

	/**
	 * 占位符结尾的后缀
	 */
	private String placeholderSuffix = SystemPropertyUtils.PLACEHOLDER_SUFFIX;

	/**
	 * 占位符变量和关联的默认值之间的分隔字符（如果有）
	 */
	@Nullable
	private String valueSeparator = SystemPropertyUtils.VALUE_SEPARATOR;

	private final Set<String> requiredProperties = new LinkedHashSet<>();


	@Override
	public ConfigurableConversionService getConversionService() {
		// Need to provide an independent DefaultConversionService, not the
		// shared DefaultConversionService used by PropertySourcesPropertyResolver.
		ConfigurableConversionService cs = this.conversionService;
		if (cs == null) {
			synchronized (this) {
				cs = this.conversionService;
				if (cs == null) {
					cs = new DefaultConversionService();
					this.conversionService = cs;
				}
			}
		}
		return cs;
	}

	@Override
	public void setConversionService(ConfigurableConversionService conversionService) {
		Assert.notNull(conversionService, "ConversionService must not be null");
		this.conversionService = conversionService;
	}

	/**
	 * Set the prefix that placeholders replaced by this resolver must begin with.
	 * <p>The default is "${".
	 *
	 * @see org.springframework.util.SystemPropertyUtils#PLACEHOLDER_PREFIX
	 */
	@Override
	public void setPlaceholderPrefix(String placeholderPrefix) {
		Assert.notNull(placeholderPrefix, "'placeholderPrefix' must not be null");
		this.placeholderPrefix = placeholderPrefix;
	}

	/**
	 * Set the suffix that placeholders replaced by this resolver must end with.
	 * <p>The default is "}".
	 *
	 * @see org.springframework.util.SystemPropertyUtils#PLACEHOLDER_SUFFIX
	 */
	@Override
	public void setPlaceholderSuffix(String placeholderSuffix) {
		Assert.notNull(placeholderSuffix, "'placeholderSuffix' must not be null");
		this.placeholderSuffix = placeholderSuffix;
	}

	/**
	 * Specify the separating character between the placeholders replaced by this
	 * resolver and their associated default value, or {@code null} if no such
	 * special character should be processed as a value separator.
	 * <p>The default is ":".
	 *
	 * @see org.springframework.util.SystemPropertyUtils#VALUE_SEPARATOR
	 */
	@Override
	public void setValueSeparator(@Nullable String valueSeparator) {
		this.valueSeparator = valueSeparator;
	}

	/**
	 * Set whether to throw an exception when encountering an unresolvable placeholder
	 * nested within the value of a given property. A {@code false} value indicates strict
	 * resolution, i.e. that an exception will be thrown. A {@code true} value indicates
	 * that unresolvable nested placeholders should be passed through in their unresolved
	 * ${...} form.
	 * <p>The default is {@code false}.
	 *
	 * @since 3.2
	 */
	@Override
	public void setIgnoreUnresolvableNestedPlaceholders(boolean ignoreUnresolvableNestedPlaceholders) {
		this.ignoreUnresolvableNestedPlaceholders = ignoreUnresolvableNestedPlaceholders;
	}

	@Override
	public void setRequiredProperties(String... requiredProperties) {
		Collections.addAll(this.requiredProperties, requiredProperties);
	}

	/**
	 * 校验指定的必须存在的属性是否都有对应的属性值，即是否都存在。
	 */
	@Override
	public void validateRequiredProperties() {
		//初始化一个MissingRequiredPropertiesException异常对象
		MissingRequiredPropertiesException ex = new MissingRequiredPropertiesException();
		//遍历必须存在的属性名集合
		for (String key : this.requiredProperties) {
			//从属性源中尝试获取每一个属性，如果为null，即没有获取到
			if (this.getProperty(key) == null) {
				//异常实例添加这个key
				ex.addMissingRequiredProperty(key);
			}
		}
		//如果异常实例中的key不为null，那么说明存在没有值的属性，那么抛出异常
		if (!ex.getMissingRequiredProperties().isEmpty()) {
			throw ex;
		}
	}

	@Override
	public boolean containsProperty(String key) {
		return (getProperty(key) != null);
	}

	@Override
	@Nullable
	public String getProperty(String key) {
		return getProperty(key, String.class);
	}

	@Override
	public String getProperty(String key, String defaultValue) {
		String value = getProperty(key);
		return (value != null ? value : defaultValue);
	}

	@Override
	public <T> T getProperty(String key, Class<T> targetType, T defaultValue) {
		T value = getProperty(key, targetType);
		return (value != null ? value : defaultValue);
	}

	@Override
	public String getRequiredProperty(String key) throws IllegalStateException {
		String value = getProperty(key);
		if (value == null) {
			throw new IllegalStateException("Required key '" + key + "' not found");
		}
		return value;
	}

	@Override
	public <T> T getRequiredProperty(String key, Class<T> valueType) throws IllegalStateException {
		T value = getProperty(key, valueType);
		if (value == null) {
			throw new IllegalStateException("Required key '" + key + "' not found");
		}
		return value;
	}

	@Override
	public String resolvePlaceholders(String text) {
		if (this.nonStrictHelper == null) {
			this.nonStrictHelper = createPlaceholderHelper(true);
		}
		return doResolvePlaceholders(text, this.nonStrictHelper);
	}

	/**
	 * 该方法是位于PropertySourcesPropertyResolver的父类AbstractPropertyResolver中的方法
	 * <p>解析必须的给定文本中的占位符，默认占位符语法规则为 ${...}
	 * @param text 原始的字符串文本
	 * @return 已解析的字符串
	 * @throws IllegalArgumentException 如果给定文本为null
	 */
	@Override
	public String resolveRequiredPlaceholders(String text) throws IllegalArgumentException {
		//创建一个属性占位符解析辅助对象  PropertyPlaceholderHelper
		if (this.strictHelper == null) {
			this.strictHelper = createPlaceholderHelper(false);
		}
		//调用doResolvePlaceholders方法，解析占位符
		return doResolvePlaceholders(text, this.strictHelper);
	}

	/**
	 * Resolve placeholders within the given string, deferring to the value of
	 * {@link #setIgnoreUnresolvableNestedPlaceholders} to determine whether any
	 * unresolvable placeholders should raise an exception or be ignored.
	 * <p>Invoked from {@link #getProperty} and its variants, implicitly resolving
	 * nested placeholders. In contrast, {@link #resolvePlaceholders} and
	 * {@link #resolveRequiredPlaceholders} do <i>not</i> delegate
	 * to this method but rather perform their own handling of unresolvable
	 * placeholders, as specified by each of those methods.
	 *
	 * @see #setIgnoreUnresolvableNestedPlaceholders
	 * @since 3.2
	 */
	protected String resolveNestedPlaceholders(String value) {
		if (value.isEmpty()) {
			return value;
		}
		return (this.ignoreUnresolvableNestedPlaceholders ?
				resolvePlaceholders(value) : resolveRequiredPlaceholders(value));
	}

	/**
	 * 创建属性占位符辅助对象
	 *
	 * @param ignoreUnresolvablePlaceholders 指示是否应忽略无法解析的占位符，true 忽略 false 抛出异常
	 * @return
	 */
	private PropertyPlaceholderHelper createPlaceholderHelper(boolean ignoreUnresolvablePlaceholders) {
		//调用PropertyPlaceholderHelper的构造器
		//传递默认的占位符解析格式  前缀"${"   后缀"}"   占位符变量和默认值的分隔符":"
		//ignoreUnresolvablePlaceholders=true，即在无法解析占位符的时候忽略
		return new PropertyPlaceholderHelper(this.placeholderPrefix, this.placeholderSuffix,
				this.valueSeparator, ignoreUnresolvablePlaceholders);
	}

	/**
	 * 实际上内部调用属性占位符辅助对象的replacePlaceholders方法，解析占位符
	 */
	private String doResolvePlaceholders(String text, PropertyPlaceholderHelper helper) {
		return helper.replacePlaceholders(text, this::getPropertyAsRawString);
		//这个方法引用有点绕，表示resolvePlaceholder方法调用当前PropertySourcesPropertyResolver对象的getPropertyAsRawString方法
		//使用普通匿名内部类格式如下：
		//return helper.replacePlaceholders(text, new PropertyPlaceholderHelper.PlaceholderResolver() {
		//    @Override
		//    public String resolvePlaceholder(String key) {
		//        return getPropertyAsRawString(key);
		//    }
		//});
	}

	/**
	 * Convert the given value to the specified target type, if necessary.
	 * 如有必要，将给定值转换为指定的目标类型。
	 *
	 * @param value      the original property value 原始属性值
	 * @param targetType the specified target type for property retrieval 属性检索的指定目标类型
	 * @return the converted value, or the original value if no conversion 转换后的值，如果不需要转换则返回原始值
	 * is necessary
	 * @since 4.3.5
	 */
	@SuppressWarnings("unchecked")
	@Nullable
	protected <T> T convertValueIfNecessary(Object value, @Nullable Class<T> targetType) {
		//如果指定目标类型为null，那么返回原始值
		if (targetType == null) {
			return (T) value;
		}
		//获取Spring转换服务对象，这也是一个组件，用于转换类型
		ConversionService conversionServiceToUse = this.conversionService;
		//如果为null
		if (conversionServiceToUse == null) {
			// Avoid initialization of shared DefaultConversionService if
			// no standard type conversion is needed in the first place...
			//是否等于给定类型，一般都不相等，除了字符串类型
			if (ClassUtils.isAssignableValue(targetType, value)) {
				return (T) value;
			}
			//调用DefaultConversionService的静态方法获取共享的转换服务实例，这里是单例模式的懒汉模式应用
			conversionServiceToUse = DefaultConversionService.getSharedInstance();
		}
		//实际上就是调用DefaultConversionService的convert方法，将会查找适合的转换器，并尝试转换
		return conversionServiceToUse.convert(value, targetType);
	}


	/**
	 * Retrieve the specified property as a raw String,
	 * i.e. without resolution of nested placeholders.
	 *
	 * @param key the property name to resolve
	 * @return the property value or {@code null} if none found
	 */
	@Nullable
	protected abstract String getPropertyAsRawString(String key);

}
