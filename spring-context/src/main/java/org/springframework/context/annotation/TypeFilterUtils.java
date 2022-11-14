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

package org.springframework.context.annotation;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AspectJTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.util.Assert;

/**
 * Collection of utilities for working with {@link ComponentScan @ComponentScan}
 * {@linkplain ComponentScan.Filter type filters}.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 5.3.13
 * @see ComponentScan.Filter
 * @see org.springframework.core.type.filter.TypeFilter
 */
public abstract class TypeFilterUtils {

	/**
	 * Create {@linkplain TypeFilter type filters} from the supplied
	 * {@link AnnotationAttributes}, such as those sourced from
	 * {@link ComponentScan#includeFilters()} or {@link ComponentScan#excludeFilters()}.
	 * <p>Each {@link TypeFilter} will be instantiated using an appropriate
	 * constructor, with {@code BeanClassLoaderAware}, {@code BeanFactoryAware},
	 * {@code EnvironmentAware}, and {@code ResourceLoaderAware} contracts
	 * invoked if they are implemented by the type filter.
	 * @param filterAttributes {@code AnnotationAttributes} for a
	 * {@link ComponentScan.Filter @Filter} declaration
	 * @param environment the {@code Environment} to make available to filters
	 * @param resourceLoader the {@code ResourceLoader} to make available to filters
	 * @param registry the {@code BeanDefinitionRegistry} to make available to filters
	 * as a {@link org.springframework.beans.factory.BeanFactory} if applicable
	 * @return a list of instantiated and configured type filters
	 * @see TypeFilter
	 * @see AnnotationTypeFilter
	 * @see AssignableTypeFilter
	 * @see AspectJTypeFilter
	 * @see RegexPatternTypeFilter
	 * @see org.springframework.beans.factory.BeanClassLoaderAware
	 * @see org.springframework.beans.factory.BeanFactoryAware
	 * @see org.springframework.context.EnvironmentAware
	 * @see org.springframework.context.ResourceLoaderAware
	 */
	public static List<TypeFilter> createTypeFiltersFor(AnnotationAttributes filterAttributes, Environment environment,
			ResourceLoader resourceLoader, BeanDefinitionRegistry registry) {
		//用于保存解析出来的类型过滤器
		List<TypeFilter> typeFilters = new ArrayList<>();
		//获取type属性值
		FilterType filterType = filterAttributes.getEnum("type");
		/*
		 * 获取classes属性值，遍历
		 */
		for (Class<?> filterClass : filterAttributes.getClassArray("classes")) {
			switch (filterType) {
				//添加注解类型过滤器
				case ANNOTATION -> {
					Assert.isAssignable(Annotation.class, filterClass,
							"@ComponentScan ANNOTATION type filter requires an annotation type");
					@SuppressWarnings("unchecked")
					Class<Annotation> annotationType = (Class<Annotation>) filterClass;
					typeFilters.add(new AnnotationTypeFilter(annotationType));
				}
				//添加类或者接口的类型过滤器
				case ASSIGNABLE_TYPE -> typeFilters.add(new AssignableTypeFilter(filterClass));
				//添加自定义类型过滤器
				case CUSTOM -> {
					Assert.isAssignable(TypeFilter.class, filterClass,
							"@ComponentScan CUSTOM type filter requires a TypeFilter implementation");
					TypeFilter filter = ParserStrategyUtils.instantiateClass(filterClass, TypeFilter.class,
							environment, resourceLoader, registry);
					typeFilters.add(filter);
				}
				//其他类型的过滤器将抛出异常
				default ->
					throw new IllegalArgumentException("Filter type not supported with Class value: " + filterType);
			}
		}

		/*
		 * 获取pattern属性值，遍历
		 */
		for (String expression : filterAttributes.getStringArray("pattern")) {
			switch (filterType) {
				//创建一个AspectJTypeFilter，expression应该是一个AspectJ表达式，通过该表达式匹配类或者接口（及其子类、子接口）
				case ASPECTJ -> typeFilters.add(new AspectJTypeFilter(expression, resourceLoader.getClassLoader()));
				//创建一个RegexPatternTypeFilter，expression应该是一个正则表达式，通过该表达式匹配类或者接口（及其子类、子接口）
				case REGEX -> typeFilters.add(new RegexPatternTypeFilter(Pattern.compile(expression)));
				//其他类型的过滤器将抛出异常
				default ->
					throw new IllegalArgumentException("Filter type not supported with String pattern: " + filterType);
			}
		}
		//返回解析出来的类型过滤器集合
		return typeFilters;
	}

}
