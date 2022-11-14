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

package org.springframework.context.index;

import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.util.AntPathMatcher;
import org.springframework.util.ClassUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Provide access to the candidates that are defined in {@code META-INF/spring.components}.
 *
 * <p>An arbitrary number of stereotypes can be registered (and queried) on the index: a
 * typical example is the fully qualified name of an annotation that flags the class for
 * a certain use case. The following call returns all the {@code @Component}
 * <b>candidate</b> types for the {@code com.example} package (and its sub-packages):
 * <pre class="code">
 * Set&lt;String&gt; candidates = index.getCandidateTypes(
 *         "com.example", "org.springframework.stereotype.Component");
 * </pre>
 *
 * <p>The {@code type} is usually the fully qualified name of a class, though this is
 * not a rule. Similarly, the {@code stereotype} is usually the fully qualified name of
 * a target type but it can be any marker really.
 *
 * @author Stephane Nicoll
 * @since 5.0
 */
public class CandidateComponentsIndex {

	private static final AntPathMatcher pathMatcher = new AntPathMatcher(".");

	private final MultiValueMap<String, Entry> index;


	CandidateComponentsIndex(List<Properties> content) {
		this.index = parseIndex(content);
	}

	private static MultiValueMap<String, Entry> parseIndex(List<Properties> content) {
		MultiValueMap<String, Entry> index = new LinkedMultiValueMap<>();
		//遍历Properties
		for (Properties entry : content) {
			entry.forEach((type, values) -> {
				//按照","拆分value为stereotypes数组
				String[] stereotypes = ((String) values).split(",");
				//遍历stereotypes数组
				for (String stereotype : stereotypes) {
					//将stereotype作为key，一个新Entry作为value加入到index映射中，这里的Entry是CandidateComponentsIndex的一个内部类
					//注意，由于是LinkedMultiValueMap类型的映射，它非常特别，对于相同的key，它的value不会被替换，而是采用一个LinkedList将value都保存起来
					//比如，如果有两个键值对，key都为a，value分别为b、c，那么添加两个键值对之后，map中仍然只有一个键值对，key为a，但是value是一个LinkedList，内部有两个值，即b和c
					index.add(stereotype, new Entry((String) type));
				}
			});
		}
		return index;
	}


	/**
	 * Return the candidate types that are associated with the specified stereotype.
	 * 返回满足条件的bean类型（全路径类名）
	 * @param basePackage the package to check for candidates
	 * @param stereotype the stereotype to use
	 * @return the candidate types associated with the specified {@code stereotype}
	 * or an empty set if none has been found for the specified {@code basePackage}
	 */
	public Set<String> getCandidateTypes(String basePackage, String stereotype) {
		//获取使用指定注解或者类(接口)的全路径名的作为key的value集合
		List<Entry> candidates = this.index.get(stereotype);
		//如果candidates不为
		if (candidates != null) {
			//使用lambda的并行流处理，如果当前bean类型属于指定的包路径中，则表示满足条件，并且收集到set集合中
			return candidates.parallelStream()
					//匹配包路径
					.filter(t -> t.match(basePackage))
					//获取type，实际上就是文件的key，即bean组件的类的全路径类名
					.map(t -> t.type)
					.collect(Collectors.toSet());
		}
		//返回空集合
		return Collections.emptySet();
	}


	private static class Entry {

		private final String type;

		private final String packageName;

		Entry(String type) {
			this.type = type;
			this.packageName = ClassUtils.getPackageName(type);
		}

		public boolean match(String basePackage) {
			if (pathMatcher.isPattern(basePackage)) {
				return pathMatcher.match(basePackage, this.packageName);
			}
			else {
				return this.type.startsWith(basePackage);
			}
		}
	}

}
