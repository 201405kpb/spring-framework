/*
 * Copyright 2002-2023 the original author or authors.
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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.core.CollectionFactory;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;
import org.springframework.util.NumberUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import java.beans.PropertyEditor;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * Internal helper class for converting property values to target types.
 * <p>用于将属性值转换为目标类型的内部助手类</p>
 *
 * <p>Works on a given {@link PropertyEditorRegistrySupport} instance.
 * Used as a delegate by {@link BeanWrapperImpl} and {@link SimpleTypeConverter}.
 * <p>工作在一个给定 PropertyEditorRegistrySupport实例。由 BeanWrapperImpl 和 SimpleTypeConverter 用作委托</p>
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Dave Syer
 * @since 2.0
 * @see BeanWrapperImpl
 * @see SimpleTypeConverter
 */
class TypeConverterDelegate {

	private static final Log logger = LogFactory.getLog(TypeConverterDelegate.class);

	/**
	 * 要使用的属性编辑器注册表
	 */
	private final PropertyEditorRegistrySupport propertyEditorRegistry;

	/**
	 * 要处理的目标对象（作为可传递给编辑器的上下文）
	 */
	@Nullable
	private final Object targetObject;


	/**
	 * Create a new TypeConverterDelegate for the given editor registry.
	 * <p>创建一个新的 TypeConverterDelegate 实例</p>
	 * @param propertyEditorRegistry the editor registry to use -- 要使用的属性编辑器注册表
	 */
	public TypeConverterDelegate(PropertyEditorRegistrySupport propertyEditorRegistry) {
		this(propertyEditorRegistry, null);
	}

	/**
	 * Create a new TypeConverterDelegate for the given editor registry and bean instance.
	 * <p>为给定的编辑器注册表和Bean实例创建一个新的TypeConverterDelegate</p>
	 * @param propertyEditorRegistry the editor registry to use -- 要使用的编辑器注册表
	 * @param targetObject the target object to work on (as context that can be passed to editors)
	 *                     -- 要处理的目标对象（作为可传递给编辑器的上下文）
	 */
	public TypeConverterDelegate(PropertyEditorRegistrySupport propertyEditorRegistry, @Nullable Object targetObject) {
		this.propertyEditorRegistry = propertyEditorRegistry;
		this.targetObject = targetObject;
	}


	/**
	 * Convert the value to the required type for the specified property.
	 * <p>将该值转换为 指定属性 所需的类型</p>
	 * @param propertyName name of the property -- 属性名
	 * @param oldValue the previous value, if available (may be {@code null})
	 *                 -- 前一个值(如果可用)(可以为null)
	 * @param newValue the proposed new value
	 *                 -- 新的值
	 * @param requiredType the type we must convert to
	 * (or {@code null} if not known, for example in case of a collection element)
	 *                     -- 必须转换为的类型(如果不知道，例如集合元素，则为null)
	 * @return the new value, possibly the result of type conversion
	 * 	-- 新值，可能是类型转换的结果
	 * @throws IllegalArgumentException if type conversion failed
	 * 		-- 如果转化失败
	 */
	@Nullable
	public <T> T convertIfNecessary(@Nullable String propertyName, @Nullable Object oldValue,
									Object newValue, @Nullable Class<T> requiredType) throws IllegalArgumentException {
		//将requiredType封装成TypeDescriptor对象，调用另外一个重载方法
		return convertIfNecessary(propertyName, oldValue, newValue, requiredType, TypeDescriptor.valueOf(requiredType));
	}

	/**
	 * Convert the value to the required type (if necessary from a String),
	 * for the specified property.
	 * <p>将指定属性的值转换为所需的类型(如果需要 从 字符串)</p>
	 * <ol>
	 *  <li>如果找不到在PropertyEditorRegistry中requireType对应的属性编辑器，但能拿到ConversionService对象进行对newValue转换为requireType类型对象，并将结果
	 *  返回出去，如果转换失败，会使用conversionAttemptEx保存异常对象</li>
	 *  <li>使用 requireType对应的属性编辑器对 newValue进行转换为【转换后的对象不一定是requiredType类型的对象】【convertedValue】</li>
	 *  <li>convertedValue 不为 null的情况下 ：
	 *   <ol>
	 *     <li>如果requiredType是Object类型,就返回 convertedValue </li>
	 *     <li>如果convertedValue是Collection对象,将 convertedValue 转换为 Collection 类型对象  </li>
	 *     <li>如果convertedValue是 Map类型对象,将 convertedValue 转换为 Map类型对象 </li>
	 *     <li>如果convertedValues是数组类型 && convertedValue的数组长度为1,convertedValue引用自己的第一个元素对象</li>
	 *     <li>如果 requiredType 是 String 类型 && convertedValue是原始类型/原始包装类型,将convertedValue转换为字符串转返回出去</li>
	 *     <li>如果 convertedValue 是 String 类型 && convertedValue不是requiredType类型:
	 *      <ol>
	 *         <li> 如果conversionAttemptEx 为 null && requiredType不是接口 && requireType不是枚举类，获取requiredType的接收一个String类型参数的
	 *         构造函数对象，并使用该构造函数传入 convertedValue 实例化对象并返回出去</li>
	 *         <li>如果 requireType是枚举 && convertedValue不是空字符传，就尝试转换String对象为Enum对象convertedValue转换为Number类型对象</li>
	 *      </ol>
	 *     </li>
	 *     <li>如果convertedValue是Number实例 && requiredType是Number的实现或子类，将 convertedValue转换为Number类型对象</li>
	 *   </ol>
	 *  </li>
	 *  <li>convertedValue == null && requiredType为Optional类,convertedValue就为Optional空对象</li>
	 *  <li>如果 convertedValue 不是 requiredType 的实例 && conversionAttemptEx为null && conversionService不为null && typeDescriptor 不为null,
	 *  将 newValue 转换为 typeDescriptor 对应类型的对象，然后返回出去。</li>
	 *  <li>将convertedValue返回出去</li>
	 * </ol>
	 * @param propertyName name of the property -- 属性名
	 * @param oldValue the previous value, if available (may be {@code null})
	 *                -- 旧属性值，可以是 null
	 * @param newValue the proposed new value
	 *                 -- 新属性值
	 * @param requiredType the type we must convert to
	 * (or {@code null} if not known, for example in case of a collection element)
	 *                        -- 要装换的类型，必须转换为的类型(如果不知道，例如集合元素，则为null)
	 * @param typeDescriptor the descriptor for the target property or field
	 *                       -- 目标属性 或 字段的描述符
	 * @return the new value, possibly the result of type conversion
	 * 		-- 新值，可能类型转换的结果
	 * @throws IllegalArgumentException if type conversion failed -- 如果类型转换失败
	 */
	@SuppressWarnings("unchecked")
	@Nullable
	public <T> T convertIfNecessary(@Nullable String propertyName, @Nullable Object oldValue, @Nullable Object newValue,
									@Nullable Class<T> requiredType, @Nullable TypeDescriptor typeDescriptor) throws IllegalArgumentException {

		// Custom editor for this type?
		// 自定义编辑这个类型吗？
		//PropertyEditor是属性编辑器的接口，它规定了将外部设置值转换为内部JavaBean属性值的转换接口方法。
		// 为requiredType 和 propertyName找到一个自定义属性编辑器
		PropertyEditor editor = this.propertyEditorRegistry.findCustomEditor(requiredType, propertyName);
		//尝试使用自定义ConversionService转换newValue转换失败后抛出的异常
		ConversionFailedException conversionAttemptEx = null;

		// No custom editor but custom ConversionService specified?
		// 没有自定以编辑器，但自定以 ConversionService 指定了？
		// ConversionService :  一个类型转换的服务接口。这个转换系统的入口。
		//获取 类型转换服务
		ConversionService conversionService = this.propertyEditorRegistry.getConversionService();
		//如果 editor 为null 且 conversionService 不为null && 新值不为null && 类型描述符不为null
		if (editor == null && conversionService != null && newValue != null && typeDescriptor != null) {
			//将newValue封装成TypeDescriptor对象
			TypeDescriptor sourceTypeDesc = TypeDescriptor.forObject(newValue);
			//如果sourceTypeDesc的对象能被转换成typeDescriptor.
			if (conversionService.canConvert(sourceTypeDesc, typeDescriptor)) {
				try {
					//从conversionService 中找到 sourceTypeDesc,typeDescriptor对于的转换器进行对newValue的转换成符合
					// 	-- typeDescriptor类型的对象，并返回出去
					return (T) conversionService.convert(newValue, sourceTypeDesc, typeDescriptor);
				}
				catch (ConversionFailedException ex) {
					//捕捉转换失败异常
					// fallback to default conversion logic below
					// 返回到下面的默认转换逻辑
					conversionAttemptEx = ex;
				}
			}
		}

		//默认转换后的值为newValue
		Object convertedValue = newValue;

		// Value not of required type?
		// 值不是必需的类型
		// 如果editor不为null || (requiredType不为null && convertedValue不是requiredType的实例)
		if (editor != null || (requiredType != null && !ClassUtils.isAssignableValue(requiredType, convertedValue))) {
			// 如果 typeDescriptor 不为null && requiredType 不为null && requiredType是Collection的子类或实现 && coventedValue 是 String类型
			if (typeDescriptor != null && requiredType != null && Collection.class.isAssignableFrom(requiredType) &&
					convertedValue instanceof String text) {
				//获取该 typeDescriptor的元素TypeDescriptor
				TypeDescriptor elementTypeDesc = typeDescriptor.getElementTypeDescriptor();
				//如果elementTypeDesc不为null
				if (elementTypeDesc != null) {
					//获取elementTypeDesc的类型
					Class<?> elementType = elementTypeDesc.getType();
					//如果elementType是Class类 || elementType 是 Enum 的子类或实现
					if (Class.class == elementType || Enum.class.isAssignableFrom(elementType)) {
						//将convertedValue强转为String，以 逗号 分割 convertedValue 返回空字符串
						convertedValue = StringUtils.commaDelimitedListToStringArray(text);
					}
				}
			}
			//如果editor为null
			if (editor == null) {
				//找到requiredType的默认编辑器
				editor = findDefaultEditor(requiredType);
			}
			//使用editor将convertedValue转换为requiredType
			convertedValue = doConvertValue(oldValue, convertedValue, requiredType, editor);
		}

		//标准转换标记，convertedValue是Collection类型，Map类型，数组类型，可转换成Enum类型的String对象，Number类型并成功进行转换后即为
		// true
		boolean standardConversion = false;

		//如果requiredType不为null
		if (requiredType != null) {
			// Try to apply some standard type conversion rules if appropriate.
			// 如果合适，尝试应用一些标准类型转换规则
			// convertedValue不为null
			if (convertedValue != null) {
				//如果requiredType是Object类型
				if (Object.class == requiredType) {
					//直接返回convertedValue
					return (T) convertedValue;
				}
				//如果requiredType是数组
				else if (requiredType.isArray()) {
					// Array required -> apply appropriate conversion of elements.
					// 数组所需 -> 应用适当的元素转换
					//如果convertedValue是String的实例 && requiredType的元素类型是Enum的子类或实现
					if (convertedValue instanceof String text && Enum.class.isAssignableFrom(requiredType.getComponentType())) {
						// 将逗号分割的列表(例如 csv 文件中的一行)转换为字符串数组
						convertedValue = StringUtils.commaDelimitedListToStringArray(text);
					}
					//将 convertedValue 转换为 ComponentType类型数组对象
					return (T) convertToTypedArray(convertedValue, propertyName, requiredType.getComponentType());
				}
				//如果convertedValue是Collection对象
				else if (convertedValue instanceof Collection<?> coll) {
					// Convert elements to target type, if determined.
					// 如果确定，则将元素转换为目标类型
					// 将 convertedValue 转换为 Collection 类型 对象
					convertedValue = convertToTypedCollection(
							coll, propertyName, requiredType, typeDescriptor);
					// 更新 standardConversion 标记
					standardConversion = true;
				}
				//如果convertedValue是 Map 对象
				else if (convertedValue instanceof Map<?, ?> map) {
					// Convert keys and values to respective target type, if determined.
					// 如果确定了，则将建和值转换为相应的目标类型
					convertedValue = convertToTypedMap(
							map, propertyName, requiredType, typeDescriptor);
					// 更新 standardConversion 标记
					standardConversion = true;
				}
				//如果convertedValue是数组类型 && convertedValue的数组长度为1
				if (convertedValue.getClass().isArray() && Array.getLength(convertedValue) == 1) {
					//获取convertedValue的第一个元素对象
					convertedValue = Array.get(convertedValue, 0);
					//更新 standardConversion 标记
					standardConversion = true;
				}
				//如果 requiredType 是 String 类型 && convertedValue是原始类型/原始包装类型
				if (String.class == requiredType && ClassUtils.isPrimitiveOrWrapper(convertedValue.getClass())) {
					// We can stringify any primitive value...
					// 我们可以 字符串化 任何原始值
					// 将convertedValue转换为字符转返回出去
					return (T) convertedValue.toString();
				}
				//如果 convertedValue 是 String 类型 && convertedValue不是requiredType类型
				else if (convertedValue instanceof String text && !requiredType.isInstance(convertedValue)) {
					//conversionAttemptEx 为 null 意味着 自定义ConversionService转换newValue转换失败 或者 没有自定义ConversionService
					// 如果conversionAttemptEx 为 null && requiredType不是接口 && requireType不是枚举类
					if (conversionAttemptEx == null && !requiredType.isInterface() && !requiredType.isEnum()) {
						try {
							//获取requiredType的接收一个String类型参数的构造函数对象
							Constructor<T> strCtor = requiredType.getConstructor(String.class);
							//使用 strCtor 构造函数，传入 convertedValue 实例化对象并返回出去
							return BeanUtils.instantiateClass(strCtor, convertedValue);
						}
						catch (NoSuchMethodException ex) {//捕捉 找不到 接收一个String类型参数的构造函数 的异常
							// proceed with field lookup
							// 继续字段查找
							// 如果 当前日志是 跟踪模式
							if (logger.isTraceEnabled()) {
								// 打印日志：没有找到 [requireType类名] 的接收一个String类型参数的构造函数
								logger.trace("No String constructor found on type [" + requiredType.getName() + "]", ex);
							}
						}
						catch (Exception ex) {//捕捉 接收一个String类型参数的构造函数 的异常
							// 如果 当前日志是 调式模式
							if (logger.isDebugEnabled()) {
								// 打印日志：通过 接收一个String类型参数的构造函数 构造[requireType类型]对象 失败
								logger.debug("Construction via String failed for type [" + requiredType.getName() + "]", ex);
							}
						}
					}
					//将convertedValue强转为字符串，并去掉前后的空格
					String trimmedValue = text.trim();
					//如果 requireType是枚举 && trimmedValue 是空字符串
					if (requiredType.isEnum() && trimmedValue.isEmpty()) {
						// It's an empty enum identifier: reset the enum value to null.
						// 这个一个空枚举标识符：重置枚举值为null
						return null;
					}
					//尝试转换String对象为Enum对象
					convertedValue = attemptToConvertStringToEnum(requiredType, trimmedValue, convertedValue);
					//更新 standardConversion 标记
					standardConversion = true;
				}
				//如果convertedValue是Number实例 && requiredType是Number的实现或子类
				else if (convertedValue instanceof Number num && Number.class.isAssignableFrom(requiredType)) {
					//NumberUtils.convertNumberToTargetClass：将convertedValue为requiredType的实例
					convertedValue = NumberUtils.convertNumberToTargetClass(
							num, (Class<Number>) requiredType);
					//更新 standardConversion 标记
					standardConversion = true;
				}
			}
			else {// convertedValue == null
				//如果requiredType为Optional类
				if (requiredType == Optional.class) {
					//将convertedValue设置Optional空对象
					convertedValue = Optional.empty();
				}
			}
			//如果 convertedValue 不是 requiredType 的实例
			if (!ClassUtils.isAssignableValue(requiredType, convertedValue)) {
				//conversionAttemptEx：尝试使用自定义ConversionService转换newValue转换失败后抛出的异常
				//conversionAttemptEx不为null
				if (conversionAttemptEx != null) {
					// Original exception from former ConversionService call above...
					// 从前面的 ConversionService 调用的原始异常
					//重新抛出 conversionAttemptEx
					throw conversionAttemptEx;
				}
				//如果conversionService不为null && typeDescriptor 不为null
				else if (conversionService != null && typeDescriptor != null) {
					// ConversionService not tried before, probably custom editor found
					// but editor couldn't produce the required type...
					// ConversionService之前没有尝试过，可能找到了 自定义编辑器，但编辑器不能产生所需的类型
					//获取newValue的类型描述符
					TypeDescriptor sourceTypeDesc = TypeDescriptor.forObject(newValue);
					//如果sourceTypeDesc 的对象能被转换成 typeDescriptor
					if (conversionService.canConvert(sourceTypeDesc, typeDescriptor)) {
						//将 newValue 转换为 typeDescriptor 对应类型的对象
						return (T) conversionService.convert(newValue, sourceTypeDesc, typeDescriptor);
					}
				}

				// Definitely doesn't match: throw IllegalArgumentException/IllegalStateException
				// 绝对不匹配：抛出  IllegalArgumentException/IllegalStateException
				// 拼接异常信息
				StringBuilder msg = new StringBuilder();
				msg.append("Cannot convert value of type '").append(ClassUtils.getDescriptiveType(newValue));
				msg.append("' to required type '").append(ClassUtils.getQualifiedName(requiredType)).append("'");
				if (propertyName != null) {
					msg.append(" for property '").append(propertyName).append("'");
				}
				if (editor != null) {
					msg.append(": PropertyEditor [").append(editor.getClass().getName()).append(
							"] returned inappropriate value of type '").append(
							ClassUtils.getDescriptiveType(convertedValue)).append("'");
					throw new IllegalArgumentException(msg.toString());
				}
				else {
					msg.append(": no matching editors or conversion strategy found");
					throw new IllegalStateException(msg.toString());
				}
			}
		}
		//conversionAttemptEx：尝试使用自定义ConversionService转换newValue转换失败后抛出的异常
		//conversionAttemptEx不为null
		if (conversionAttemptEx != null) {
			//editor：requiredType 和 propertyName对应一个自定义属性编辑器
			//standardConversion:标准转换标记，convertedValue是Collection类型，Map类型，数组类型，可转换成Enum类型的String对象，Number类型并成功
			// 		进行转换后即为true
			// editor 为 null && 不是标准转换 && 要转换的类型不为null && requiedType不是Object类
			if (editor == null && !standardConversion && requiredType != null && Object.class != requiredType) {
				//重新抛出 conversionAttemptEx
				throw conversionAttemptEx;
			}
			//如果日志级别是调试级别：最初的ConversionService尝试失败————此后被忽略基于PropertyEditor的转换最终成功
			logger.debug("Original ConversionService attempt failed - ignored since " +
					"PropertyEditor based conversion eventually succeeded", conversionAttemptEx);
		}
		//返回转换后的值
		return (T) convertedValue;
	}

	/**
	 * 尝试转换String对象为Enum对象
	 * @param requiredType 要装换的类型，必须转换为的类型
	 * @param trimmedValue 要转换的字符传，要保证其 前后没有空格
	 * @param currentConvertedValue 当前转换后的值
	 */
	private Object attemptToConvertStringToEnum(Class<?> requiredType, String trimmedValue, Object currentConvertedValue) {
		//当前转换后的对象，默认是 currentConvertedValue
		Object convertedValue = currentConvertedValue;

		//如果 requiredType是 Enum 类 && 目标对象不为null
		if (Enum.class == requiredType && this.targetObject != null) {
			// target type is declared as raw enum, treat the trimmed value as <enum.fqn>.FIELD_NAME
			// 目标类型被声明为原始枚举，处理修减值为 <enum.fqn>.FIELD_NAME
			//将trimmedValue的最后一个'.'位置
			int index = trimmedValue.lastIndexOf('.');
			//如果找到'.'的位置
			if (index > - 1) {
				//截取出trimmedValue的index前面的字符传作为枚举类名
				String enumType = trimmedValue.substring(0, index);
				//截取出trimmedValue的index+1后面的字符传为枚举类的属性名
				String fieldName = trimmedValue.substring(index + 1);
				//获取targetObject的类加载器
				ClassLoader cl = this.targetObject.getClass().getClassLoader();
				try {
					//从cl中获取enumType的Class对象
					Class<?> enumValueType = ClassUtils.forName(enumType, cl);
					//获取fieldName 对应  enumValueType 属性对象
					Field enumField = enumValueType.getField(fieldName);
					//取出该属性对象的值作为 convertedValue
					convertedValue = enumField.get(null);
				}
				catch (ClassNotFoundException ex) {//捕捉未找到类异常
					//如果当前日志级别是 跟踪：没法价值 [enumType] 枚举类
					if (logger.isTraceEnabled()) {
						logger.trace("Enum class [" + enumType + "] cannot be loaded", ex);
					}
				}
				catch (Throwable ex) {//捕捉获取枚举对象的所有异常
					//如果当前日志级别是跟踪：属性[fieldName]不是[enumType]枚举类的枚举值
					if (logger.isTraceEnabled()) {
						logger.trace("Field [" + fieldName + "] isn't an enum value for type [" + enumType + "]", ex);
					}
				}
			}
		}
		//如果 convertedValue 与 currentConvertedValue 是同一个对象
		if (convertedValue == currentConvertedValue) {
			// Try field lookup as fallback: for JDK 1.5 enum or custom enum
			// with values defined as static fields. Resulting value still needs
			// to be checked, hence we don't return it right away.
			// 尝试字段查找作为回退：对于JDK 1.5枚举或值定义为静态字段的自定义枚举。结果值仍然需要检查
			// 因此我们不能立即返回它
			try {
				//获取requiredType中trimmedValue属性名的属性对象
				Field enumField = requiredType.getField(trimmedValue);
				//使enumField可访问，并在需要时显示设置enumField的可访问性
				ReflectionUtils.makeAccessible(enumField);
				//取出该enumField的值作为 convertedValue
				convertedValue = enumField.get(null);
			}
			catch (Throwable ex) {//捕捉获取枚举对象的所有异常
				//如果当前日志级别是跟踪：属性[fieldName]不是枚举类的枚举值
				if (logger.isTraceEnabled()) {
					logger.trace("Field [" + convertedValue + "] isn't an enum value", ex);
				}
			}
		}
		//返回转换后的值
		return convertedValue;
	}
	/**
	 * Find a default editor for the given type.
	 * <p>找到给定类型的默认编辑器</p>
	 * @param requiredType the type to find an editor for
	 *                     	-- 要为其查找编辑器的类型
	 * @return the corresponding editor, or {@code null} if none
	 * 		-- 对应的编辑器，或 null (如果没有)
	 */
	@Nullable
	private PropertyEditor findDefaultEditor(@Nullable Class<?> requiredType) {
		PropertyEditor editor = null;
		//如果 requireType不为null
		if (requiredType != null) {
			// No custom editor -> check BeanWrapperImpl's default editors.
			// 没有自定义编辑器 -> 检查BeanWrapperImpl 的默认编辑器
			//
			editor = this.propertyEditorRegistry.getDefaultEditor(requiredType);
			if (editor == null && String.class != requiredType) {
				// No BeanWrapper default editor -> check standard JavaBean editor.
				editor = BeanUtils.findEditorByConvention(requiredType);
			}
		}
		return editor;
	}

	/**
	 * Convert the value to the required type (if necessary from a String),
	 * using the given property editor.
	 * <p>使用给定的属性编辑器将值转换为所需的类型(如果需要从String)</p>
	 * @param oldValue the previous value, if available (may be {@code null})
	 *                 	-- 前一个值(如果可用)(可以是 null)
	 * @param newValue the proposed new value
	 *                 --  建议的新值
	 * @param requiredType the type we must convert to
	 * (or {@code null} if not known, for example in case of a collection element)
	 *                     -- 必须转换为的类型(如不知道，例如集合元素，则为null)
	 * @param editor the PropertyEditor to use -- 要使用的属性编辑器
	 * @return the new value, possibly the result of type conversion
	 * 		-- 新值，可能是类型转换的结果
	 * @throws IllegalArgumentException if type conversion failed
	 * 	-- 如果类型转换失败
	 */
	@Nullable
	private Object doConvertValue(@Nullable Object oldValue, @Nullable Object newValue,
								  @Nullable Class<?> requiredType, @Nullable PropertyEditor editor) {
		//默认转换后的值为newValue
		Object convertedValue = newValue;
		//如果editor不为nll && convertedValue不是字符换
		if (editor != null && !(convertedValue instanceof String)) {
			// Not a String -> use PropertyEditor's setValue.
			// 使用 PropertyEditor 的 setValue
			// With standard PropertyEditors, this will return the very same object;
			// 使用标准的 PropertyEditors,这将返回完全相同的对象
			// we just want to allow special PropertyEditors to override setValue
			// for type conversion from non-String values to the required type.
			// 我们只是想允许特殊的PropertyEditors覆盖setValue来进行从非字符串值所需类型的类型转换
			try {
				//PropertyEditor.setValue:设置属性的值，基本类型以包装类传入（自动装箱）；
				//设置editer要编辑的对象为convertedValue
				editor.setValue(convertedValue);
				//重新获取editor的属性值
				Object newConvertedValue = editor.getValue();
				//如果newConvertedValue与convertedValue不是同一个对象
				if (newConvertedValue != convertedValue) {
					//让convertedValue引用该newConvertedValue
					convertedValue = newConvertedValue;
					// Reset PropertyEditor: It already did a proper conversion.
					// 重置 PropertyEditor:它已经做了一个适当的转换
					// Don't use it again for a setAsText call.
					// 不要在调用setAsText时再次使用它
					//PropertyEditor.setAsText：用一个字符串去更新属性的内部值，这个字符串一般从外部属性编辑器传入
					//设置editor为null
					editor = null;
				}
			}
			//捕捉特殊的PropertyEditors覆盖setValue来进行从非字符串值所需类型的类型转换时出现的所有异常
			catch (Exception ex) {
				//如果是debug模式
				if (logger.isDebugEnabled()) {
					//PropertyEditor[editor.getClass().getName()]不能提供 setValue 调用
					logger.debug("PropertyEditor [" + editor.getClass().getName() + "] does not support setValue call", ex);
				}
				// Swallow and proceed.
				// 忍受 并 继续执行
			}
		}
		//默认返回值为转换后的值
		Object returnValue = convertedValue;
		//如果 requireType不为null && requiredType不是数组 && convertedValue 是 String 数组
		if (requiredType != null && !requiredType.isArray() && convertedValue instanceof String[] array) {
			// Convert String array to a comma-separated String.
			// 将 字符串数组 转换为 逗号分割的字符串
			// Only applies if no PropertyEditor converted the String array before.
			// 只有在之前没有 PropertyEditor 转换字符串数组是才适用
			// The CSV String will be passed into a PropertyEditor's setAsText method, if any.
			// CSV字符串将被传递到 PropertyEditor的 setAsText方法中(如果有的话)
			//如果是跟踪模式
			if (logger.isTraceEnabled()) {
				//将字符串数组 转换为以逗号分割的字符串
				logger.trace("Converting String array to comma-delimited String [" + convertedValue + "]");
			}
			//将convertedValue转换为以逗号分隔的String(即CSV).
			convertedValue = StringUtils.arrayToCommaDelimitedString(array);
		}

		//如果convertedValue是String 实例
		if (convertedValue instanceof String newTextValue) {
			//如果编辑器不为null
			if (editor != null) {
				// Use PropertyEditor's setAsText in case of a String value.
				// 如果是字符值，请使用PropertyEditord的setAsText
				//如果是跟踪模式
				if (logger.isTraceEnabled()) {
					//转换字符串为[requiredType]适用属性编辑器[editor]
					logger.trace("Converting String to [" + requiredType + "] using property editor [" + editor + "]");
				}
				//使用editor转换newTextValue，并将转换后的值返回出去
				return doConvertTextValue(oldValue, newTextValue, editor);
			}
			//如果requiredType是String类型
			else if (String.class == requiredType) {
				//返回值就是convertedValue
				returnValue = convertedValue;
			}
		}
		//将返回值返回出去
		return returnValue;
	}

	/**
	 * Convert the given text value using the given property editor.
	 * <p>使用给定属性编辑器转换给定的文本值</p>
	 * @param oldValue the previous value, if available (may be {@code null})
	 *                 -- 前一个值（如果可用）（可以是 null ）
	 * @param newTextValue the proposed text value
	 *                     -- 建议的文本值
	 * @param editor the PropertyEditor to use
	 *               -- 要使用的属性编辑器
	 * @return the converted value -- 转换后的值
	 */
	private Object doConvertTextValue(@Nullable Object oldValue, String newTextValue, PropertyEditor editor) {
		try {
			//PropertyEditor.setValue:设置属性的值，基本类型以包装类传入（自动装箱）
			//设置editor的属性值为oldValue
			editor.setValue(oldValue);
		}
		catch (Exception ex) {
			//捕捉所有设置属性值时抛出的异常
			//如果是跟踪模式
			if (logger.isDebugEnabled()) {
				//属性编辑器[editor名]不支持调用 setValue
				logger.debug("PropertyEditor [" + editor.getClass().getName() + "] does not support setValue call", ex);
			}
			// Swallow and proceed.
			// 忍受 并 继续处理
		}
		//PropertyEditor.setAsText:用一个字符串去更新属性的内部值，这个字符串一般从外部属性编辑器传入；
		//使用newTextValue更新内部属性值
		editor.setAsText(newTextValue);
		//获取属性值
		return editor.getValue();
	}

	/**
	 * 将input转换为 componentType类型数组对象
	 * @param input 要转换的值
	 * @param propertyName 属性名
	 * @param componentType 数组的元素类型
	 * @return 转换的 componentType类型数组对象
	 */
	private Object convertToTypedArray(Object input, @Nullable String propertyName, Class<?> componentType) {
		//如果 input是 Collection 实例
		if (input instanceof Collection<?> coll) {
			// Convert Collection elements to array elements.
			//新建一个元素类型为componentType,长度为coll.size的列表
			Object result = Array.newInstance(componentType, coll.size());
			int i = 0;
			//遍历coll的元素
			for (Iterator<?> it = coll.iterator(); it.hasNext(); i++) {
				//将it的元素转换为componentType类型对象
				Object value = convertIfNecessary(
						buildIndexedPropertyName(propertyName, i), null, it.next(), componentType);
				//将value设置到第i个result元素位置上
				Array.set(result, i, value);
			}
			//返回result
			return result;
		}
		//如果input是数组类型
		else if (input.getClass().isArray()) {
			// Convert array elements, if necessary.
			// 如果需要，转换数组元素
			//如果componentType与input的元素类型相同 && propertyEditorRegistry不包含指定数组/集合元素的自定义编辑器
			if (componentType.equals(input.getClass().getComponentType()) &&
					!this.propertyEditorRegistry.hasCustomEditorForElement(componentType, propertyName)) {
				// 返回input
				return input;
			}
			//获取input的数组长度
			int arrayLength = Array.getLength(input);
			//构建出数组长度为arrayLength，元素类型为componentType的数组
			Object result = Array.newInstance(componentType, arrayLength);
			//遍历result
			for (int i = 0; i < arrayLength; i++) {
				//Array.get(input, i)：获取input的第i个元素对象
				//将该值转换为 componentType 对象
				Object value = convertIfNecessary(
						buildIndexedPropertyName(propertyName, i), null, Array.get(input, i), componentType);
				//将value放到result数组的第i个位置里
				Array.set(result, i, value);
			}
			//返回result
			return result;
		}
		else {//这种情况，一般就是input是普通Java对象
			// A plain value: convert it to an array with a single component.
			// 纯值：将其转换为具有单个组件的数组
			// 构建一个长度为1，元素类型为componentType的数组对象
			Object result = Array.newInstance(componentType, 1);
			//将input转换为componentType类型的对象
			Object value = convertIfNecessary(
					buildIndexedPropertyName(propertyName, 0), null, input, componentType);
			//将value设置到result第0个索引位置
			Array.set(result, 0, value);
			//返回result
			return result;
		}
	}

	/**
	 * 将 original 转换为 Collection 类型 对象
	 * @param original 原始对象
	 * @param propertyName 属性名
	 * @param requiredType 要转换的类型
	 * @param typeDescriptor 目标属性 或 字段的描述符
	 */
	@SuppressWarnings("unchecked")
	private Collection<?> convertToTypedCollection(Collection<?> original, @Nullable String propertyName,
												   Class<?> requiredType, @Nullable TypeDescriptor typeDescriptor) {
		//如果requiredType不是Collection类型
		if (!Collection.class.isAssignableFrom(requiredType)) {
			//返回original
			return original;
		}
		//collectionType是否是常见的Collection类的标记
		boolean approximable = CollectionFactory.isApproximableCollectionType(requiredType);
		//如果不是常见Collection类 且 不可以 requiredTyped 的实例
		if (!approximable && !canCreateCopy(requiredType)) {
			// 如果 日志级别是 debug, 自定义Collection类型[ 原始对象 类名]不允许创建副本-按原样注入原始集合
			if (logger.isDebugEnabled()) {
				logger.debug("Custom Collection type [" + original.getClass().getName() +
						"] does not allow for creating a copy - injecting original Collection as-is");
			}
			//返回 原始对象
			return original;
		}
		//如果orginal是requiredType的实例
		boolean originalAllowed = requiredType.isInstance(original);
		//获取 typeDescriptor 的元素类型描述符
		TypeDescriptor elementType = (typeDescriptor != null ? typeDescriptor.getElementTypeDescriptor() : null);
		//如果elementType为null && orginal是requiredType的实例 && propertyEditorRegistry不包含propertyName的null对象的自定义编辑器
		if (elementType == null && originalAllowed &&
				!this.propertyEditorRegistry.hasCustomEditorForElement(null, propertyName)) {
			//返回 原始对象
			return original;
		}
		//original 的迭代器
		Iterator<?> it;
		try {
			//获取 original 的迭代器
			it = original.iterator();
		}
		//捕捉获取迭代器发生的所有异常
		catch (Throwable ex) {
			// 如果 日志级别是 debug模式：不能访问类型为[ original类名]的Collection对象 - 按原样注入原始集合
			if (logger.isDebugEnabled()) {
				logger.debug("Cannot access Collection of type [" + original.getClass().getName() +
						"] - injecting original Collection as-is: " + ex);
			}
			// 返回 原始对象
			return original;
		}
		//转换后的 Collection 对象
		Collection<Object> convertedCopy;
		try {
			//如果requiredType是常见Collection类
			if (approximable) {
				//为original创建最近似的Collection对象，初始容量与 original 保持一致
				convertedCopy = CollectionFactory.createApproximateCollection(original, original.size());
			}
			else {
				//获取requiredType的无参构造函数，然后创建一个实例
				convertedCopy = (Collection<Object>)
						ReflectionUtils.accessibleConstructor(requiredType).newInstance();
			}
		}
		//捕捉 创建 convertedCopy 对象时抛出的异常
		catch (Throwable ex) {
			//如果 日志级别是 debug模式：不能创建 [original 类名] 的 Collection 副本对象 - 按原样注入原始集合
			if (logger.isDebugEnabled()) {
				logger.debug("Cannot create copy of Collection type [" + original.getClass().getName() +
						"] - injecting original Collection as-is: " + ex);
			}
			// 返回 原始对象
			return original;
		}
		//遍历 original 的迭代器
		for (int i = 0; it.hasNext(); i++) {
			//获取元素对象
			Object element = it.next();
			//构建索引形式的属性名。格式：propertyName[i]
			String indexedPropertyName = buildIndexedPropertyName(propertyName, i);
			//将 element 转换为 elementType 类型
			Object convertedElement = convertIfNecessary(indexedPropertyName, null, element,
					(elementType != null ? elementType.getType() : null) , elementType);
			try {
				//将 convertedElement 添加到 convertedCopy 中
				convertedCopy.add(convertedElement);
			}
			catch (Throwable ex) {
				//如果 日志级别是 debug模式： [original 类名] 的 Collection 副本对象 似乎是只读 - 按原样注入原始集合
				if (logger.isDebugEnabled()) {
					logger.debug("Collection type [" + original.getClass().getName() +
							"] seems to be read-only - injecting original Collection as-is: " + ex);
				}
				//返回 原始对象
				return original;
			}
			//更新 originalAllowed：只要 element 与 convertedElement 是同一个对象，就一直为true
			originalAllowed = originalAllowed && (element == convertedElement);
		}
		//如果 originalAllowed 为 true，就返回 original；否则返回 convertedCopy
		return (originalAllowed ? original : convertedCopy);
	}

	/**
	 * 将 original 转换为 Map 类型 对象
	 * @param original 原始对象
	 * @param propertyName 属性名
	 * @param requiredType 要转换的类型
	 * @param typeDescriptor 目标属性 或 字段的描述符
	 */
	@SuppressWarnings("unchecked")
	private Map<?, ?> convertToTypedMap(Map<?, ?> original, @Nullable String propertyName,
										Class<?> requiredType, @Nullable TypeDescriptor typeDescriptor) {
		//如果requiredType不是Collection类型
		if (!Map.class.isAssignableFrom(requiredType)) {
			//返回original
			return original;
		}

		//collectionType是否是常见的Collection类的标记
		boolean approximable = CollectionFactory.isApproximableMapType(requiredType);
		//如果不是常见Collection类 且 不可以 requiredTyped 的实例
		if (!approximable && !canCreateCopy(requiredType)) {
			// 如果 日志级别是 debug, 自定义Map类型[ 原始对象 类名]不允许创建副本-按原样注入原始集合
			if (logger.isDebugEnabled()) {
				logger.debug("Custom Map type [" + original.getClass().getName() +
						"] does not allow for creating a copy - injecting original Map as-is");
			}
			//返回 原始对象
			return original;
		}

		//如果orginal是requiredType的实例
		boolean originalAllowed = requiredType.isInstance(original);
		//如果此 TypeDescriptor是 Map 类型，则获取其Key的类型。如果 typeDescriptor 不是Map类型，将会抛出异常
		TypeDescriptor keyType = (typeDescriptor != null ? typeDescriptor.getMapKeyTypeDescriptor() : null);
		//如果此 TypeDescriptor是 Map 类型，则获取其Value的类型。如果 typeDescriptor 不是Map类型，将会抛出异常
		TypeDescriptor valueType = (typeDescriptor != null ? typeDescriptor.getMapValueTypeDescriptor() : null);
		//如果keyType 为 null  && value 为 null && orginal是requiredType的实例 && propertyEditorRegistry不包含
		// 	propertyName的null对象的自定义编辑器
		if (keyType == null && valueType == null && originalAllowed &&
				!this.propertyEditorRegistry.hasCustomEditorForElement(null, propertyName)) {
			//返回 原始对象
			return original;
		}

		//original 的迭代器
		Iterator<?> it;
		try {
			//获取 original 的迭代器
			it = original.entrySet().iterator();
		}
		//捕捉获取迭代器发生的所有异常
		catch (Throwable ex) {
			// 如果 日志级别是 debug模式：不能访问类型为[ original类名]的Map对象 - 按原样注入原始集合
			if (logger.isDebugEnabled()) {
				logger.debug("Cannot access Map of type [" + original.getClass().getName() +
						"] - injecting original Map as-is: " + ex);
			}
			// 返回 原始对象
			return original;
		}

		//转换后的 Collection 对象
		Map<Object, Object> convertedCopy;
		try {
			//如果requiredType是常见Collection类
			if (approximable) {
				//为original创建最近似的Collection对象，初始容量与 original 保持一致
				convertedCopy = CollectionFactory.createApproximateMap(original, original.size());
			}
			else {
				//获取requiredType的无参构造函数，然后创建一个实例
				convertedCopy = (Map<Object, Object>)
						ReflectionUtils.accessibleConstructor(requiredType).newInstance();
			}
		}
		//捕捉 创建 convertedCopy 对象时抛出的异常
		catch (Throwable ex) {
			//如果 日志级别是 debug模式：不能创建 [original 类名] 的 Map 副本对象 - 按原样注入原始集合
			if (logger.isDebugEnabled()) {
				logger.debug("Cannot create copy of Map type [" + original.getClass().getName() +
						"] - injecting original Map as-is: " + ex);
			}
			// 返回 原始对象
			return original;
		}

		//遍历 original 的迭代器
		while (it.hasNext()) {
			//获取元素对象
			Map.Entry<?, ?> entry = (Map.Entry<?, ?>) it.next();
			//获取entry的key对象
			Object key = entry.getKey();
			//获取entry的value对象
			Object value = entry.getValue();
			//构建键名形式的属性名。格式：propertyName[key]
			String keyedPropertyName = buildKeyedPropertyName(propertyName, key);
			//将 key 转换为 keyType 类型
			Object convertedKey = convertIfNecessary(keyedPropertyName, null, key,
					(keyType != null ? keyType.getType() : null), keyType);
			//将 value 转换为 valueType 类型
			Object convertedValue = convertIfNecessary(keyedPropertyName, null, value,
					(valueType!= null ? valueType.getType() : null), valueType);
			try {
				//将 convertedKey,convertedValue 添加到 convertedCopy 中
				convertedCopy.put(convertedKey, convertedValue);
			}
			catch (Throwable ex) {
				//如果 日志级别是 debug模式： [original 类名] 的 Collection 副本对象 似乎是只读 - 按原样注入原始集合
				if (logger.isDebugEnabled()) {
					logger.debug("Map type [" + original.getClass().getName() +
							"] seems to be read-only - injecting original Map as-is: " + ex);
				}
				//返回 原始对象
				return original;
			}
			//更新 originalAllowed：只要 element 与 convertedKey 是同一个对象 && value 与 convertedValue 是同一个对象 ，就
			// 		一直为true
			originalAllowed = originalAllowed && (key == convertedKey) && (value == convertedValue);
		}
		//如果 originalAllowed 为 true，就返回 original；否则返回 convertedCopy
		return (originalAllowed ? original : convertedCopy);
	}

	/**
	 * 构建索引形式的属性名。格式：propertyName[index]
	 * @param propertyName 属性名
	 * @param index 索引
	 * @return 索引形式的属性名；如果propertyName为null，返回null
	 */
	@Nullable
	private String buildIndexedPropertyName(@Nullable String propertyName, int index) {
		return (propertyName != null ?
				propertyName + PropertyAccessor.PROPERTY_KEY_PREFIX + index + PropertyAccessor.PROPERTY_KEY_SUFFIX :
				null);
	}

	/**
	 * 构建键名形式的属性名。格式：propertyName[key]
	 * @param propertyName 属性名
	 * @param key 键名
	 * @return 索引形式的属性名；如果propertyName为null，返回null
	 */
	@Nullable
	private String buildKeyedPropertyName(@Nullable String propertyName, Object key) {
		return (propertyName != null ?
				propertyName + PropertyAccessor.PROPERTY_KEY_PREFIX + key + PropertyAccessor.PROPERTY_KEY_SUFFIX :
				null);
	}

	/**
	 * 是否可以 requiredTyped 的实例
	 * @param requiredType 请求类型
	 */
	private boolean canCreateCopy(Class<?> requiredType) {
		//如果 requiredType不是接口 && requiredType不是抽象 && requiredType是Public && requiredType是无参构造方法 就返回true
		return (!requiredType.isInterface() && !Modifier.isAbstract(requiredType.getModifiers()) &&
				Modifier.isPublic(requiredType.getModifiers()) && ClassUtils.hasConstructor(requiredType));
	}

}