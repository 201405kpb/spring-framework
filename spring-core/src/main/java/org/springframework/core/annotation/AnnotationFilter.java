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

package org.springframework.core.annotation;

import java.lang.annotation.Annotation;

/**
 * Callback interface that can be used to filter specific annotation types.
 * 该类是一个函数式接口，用于匹配传入的注解实例、类型或名称。
 *
 * <p>Note that the {@link MergedAnnotations} model (which this interface has been
 * designed for) always ignores lang annotations according to the {@link #PLAIN}
 * filter (for efficiency reasons). Any additional filters and even custom filter
 * implementations apply within this boundary and may only narrow further from here.
 *
 * @author Phillip Webb
 * @author Juergen Hoeller
 * @since 5.2
 * @see MergedAnnotations
 */
@FunctionalInterface
public interface AnnotationFilter {

	/**
	 * {@link AnnotationFilter} that matches annotations in the
	 * {@code java.lang} and {@code org.springframework.lang} packages
	 * and their subpackages.
	 * 过滤器匹配java.lang包和spring的lang包下的注解.java.lang包下提供的都是诸如@Resource或者 @Target 这样的注解，
	 * 而springframework.lang包下提供的则都是 @Nonnull 这样的注解，这些注解基本不可能作为有特殊业务意义的元注解使用，因此默认忽略
	 * <p>This is the default filter in the {@link MergedAnnotations} model.
	 */
	AnnotationFilter PLAIN = packages("java.lang", "org.springframework.lang");

	/**
	 * {@link AnnotationFilter} that matches annotations in the
	 * {@code java} and {@code javax} packages and their subpackages.
	 * 类是否属于 java、javax包
	 */
	AnnotationFilter JAVA = packages("java", "javax");

	/**
	 * {@link AnnotationFilter} that always matches and can be used when no
	 * relevant annotation types are expected to be present at all.
	 * 所有匹配一律返回true
	 */
	AnnotationFilter ALL = new AnnotationFilter() {
		@Override
		public boolean matches(Annotation annotation) {
			return true;
		}
		@Override
		public boolean matches(Class<?> type) {
			return true;
		}
		@Override
		public boolean matches(String typeName) {
			return true;
		}
		@Override
		public String toString() {
			return "All annotations filtered";
		}
	};

	/**
	 * {@link AnnotationFilter} that never matches and can be used when no
	 * filtering is needed (allowing for any annotation types to be present).
	 * 任何匹配注解都返回false
	 * @see #PLAIN
	 * @deprecated as of 5.2.6 since the {@link MergedAnnotations} model
	 * always ignores lang annotations according to the {@link #PLAIN} filter
	 * (for efficiency reasons)
	 */
	@Deprecated
	AnnotationFilter NONE = new AnnotationFilter() {
		@Override
		public boolean matches(Annotation annotation) {
			return false;
		}
		@Override
		public boolean matches(Class<?> type) {
			return false;
		}
		@Override
		public boolean matches(String typeName) {
			return false;
		}
		@Override
		public String toString() {
			return "No annotation filtering";
		}
	};


	/**
	 * Test if the given annotation matches the filter.
	 * 判断指定注解是否匹配过滤器
	 * @param annotation the annotation to test
	 * @return {@code true} if the annotation matches
	 */
	default boolean matches(Annotation annotation) {
		return matches(annotation.annotationType());
	}

	/**
	 * Test if the given type matches the filter.
	 * 判断指定注解类型是否匹配
	 * @param type the annotation type to test
	 * @return {@code true} if the annotation matches
	 */
	default boolean matches(Class<?> type) {
		return matches(type.getName());
	}

	/**
	 * Test if the given type name matches the filter.
	 * 根据名称匹配
	 * @param typeName the fully qualified class name of the annotation type to test
	 * @param typeName 传入类型名称，其实也就是全类名
	 * @return {@code true} if the annotation matches
	 */
	boolean matches(String typeName);


	/**
	 * Create a new {@link AnnotationFilter} that matches annotations in the
	 * specified packages.
	 * @param packages the annotation packages that should match
	 * @return a new {@link AnnotationFilter} instance
	 */
	static AnnotationFilter packages(String... packages) {
		return new PackagesAnnotationFilter(packages);
	}

}
