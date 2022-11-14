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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.StringValueResolver;

/**
 * Simple implementation of the {@link AliasRegistry} interface.
 *
 * AliasRegistry接口的简单实现，用作基类BeanDefinitionRegistry的实现
 *
 * <p>Serves as base class for
 * {@link org.springframework.beans.factory.support.BeanDefinitionRegistry}
 * implementations.
 *
 * @author Juergen Hoeller
 * @author Qimiao Chen
 * @since 2.5.2
 */
public class SimpleAliasRegistry implements AliasRegistry {

	/** Logger available to subclasses.
	 * 从别名映射到规范名称
	 * */
	protected final Log logger = LogFactory.getLog(getClass());

	/**
	 * Map from alias to canonical name.
	 * <p>从别名映射到规范名称</p>
	 * <p>整个Map数据结构应该要抽象理解为一个二维数组，因为在检索别名的时候，是可以通过别名查别名的。</p>
	 * <p>举个例子:
	 *  <ol>
	 *   <li>A是B的别名，C是B的别名，存放到aliasMap中的数据结构就是:[{key=B,val=A},{key=C,val=B}]</li>
	 *   <li>当要获取A的所有别名[B,C]时:先获取A的Key->B,则通过递归形式获取B的key->C。</li>
	 *  </ol>
	 * </p>
	 * */
	private final Map<String, String> aliasMap = new ConcurrentHashMap<>(16);


	/**
	 * 注册别名和规范名称的映射关系
	 **/
	@Override
	public void registerAlias(String name, String alias) {
		Assert.hasText(name, "'name' must not be empty");
		Assert.hasText(alias, "'alias' must not be empty");
		synchronized (this.aliasMap) {
			//判定别名和规范名称是否相等
			if (alias.equals(name)) {
				//如果相等，则删除字典中的别名映射关系（如果存在）
				this.aliasMap.remove(alias);
				if (logger.isDebugEnabled()) {
					logger.debug("Alias definition '" + alias + "' ignored since it points to same name");
				}
			}
			else {
				//获取字典中指定别名的规范名称
				String registeredName = this.aliasMap.get(alias);
				//如果已经存在
				if (registeredName != null) {
					//如果规范名称已经存在于字典中，则无需重复注册
					if (registeredName.equals(name)) {
						// An existing alias - no need to re-register
						return;
					}
					//是否允许别名重写（即多个别名）
					if (!allowAliasOverriding()) {
						throw new IllegalStateException("Cannot define alias '" + alias + "' for name '" +
								name + "': It is already registered for name '" + registeredName + "'.");
					}
					if (logger.isDebugEnabled()) {
						logger.debug("Overriding alias '" + alias + "' definition for registered name '" +
								registeredName + "' with new target name '" + name + "'");
					}
				}
				//检测给定的名称是否已经作为另一个方向的别名指向给定的别名，并预先捕获循环应用
				checkForAliasCircle(name, alias);
				//将别名和规范名称加入字典中
				this.aliasMap.put(alias, name);
				if (logger.isTraceEnabled()) {
					logger.trace("Alias definition '" + alias + "' registered for name '" + name + "'");
				}
			}
		}
	}

	/**
	 * Determine whether alias overriding is allowed.
	 * 确定是否允许别名重写(即多个别名)，默认是true
	 * <p>Default is {@code true}.
	 * 在DefaultListableBeanFactory容器类中有实现方法，默认为true，允许修改
	 */
	protected boolean allowAliasOverriding() {
		return true;
	}

	/**
	 * Determine whether the given name has the given alias registered.
	 * 确定给定名称是否已注册给定别名
	 * @param name the name to check
	 * @param alias the alias to look for
	 * @since 4.2.1
	 */
	public boolean hasAlias(String name, String alias) {
		//获取字典中指定别名的规范名称（如果存在）
		String registeredName = this.aliasMap.get(alias);
		//判定给定规范名称name和获取到的规范名称registeredName是否相等，true返回，false则继续判定
		//规范名称registeredName部位null，再判定以registeredName为别名在字典中是否存在规范名称name
		return ObjectUtils.nullSafeEquals(registeredName, name) ||
				(registeredName != null && hasAlias(name, registeredName));
	}

	@Override
	public void removeAlias(String alias) {
		synchronized (this.aliasMap) {
			String name = this.aliasMap.remove(alias);
			if (name == null) {
				throw new IllegalStateException("No alias '" + alias + "' registered");
			}
		}
	}

	//判定指定名称是否是别名
	@Override
	public boolean isAlias(String name) {
		return this.aliasMap.containsKey(name);
	}

	//获取指定名称的别名数组集合
	@Override
	public String[] getAliases(String name) {
		List<String> result = new ArrayList<>();
		synchronized (this.aliasMap) {
			retrieveAliases(name, result);
		}
		return StringUtils.toStringArray(result);
	}

	/**
	 * Transitively retrieve all aliases for the given name.
	 * 检索给定名称的所有别名，如果相同就将别名加入集合
	 * @param name the target name to find aliases for
	 * @param result the resulting aliases list
	 */
	private void retrieveAliases(String name, List<String> result) {
		this.aliasMap.forEach((alias, registeredName) -> {
			if (registeredName.equals(name)) {
				result.add(alias);
				//递归检测含间接关系的别名
				retrieveAliases(alias, result);
			}
		});
	}

	/**
	 * Resolve all alias target names and aliases registered in this
	 * registry, applying the given {@link StringValueResolver} to them.
	 * 解析此注册表中注册的所有别名和目标名称，并对它们应用给定的StringValueResolver
	 * <p>The value resolver may for example resolve placeholders
	 * in target bean names and even in alias names.
	 * 例如，值解析器可以解析目标bean名称甚至别名中的占位符。
	 * @param valueResolver the StringValueResolver to apply
	 */
	public void resolveAliases(StringValueResolver valueResolver) {
		Assert.notNull(valueResolver, "StringValueResolver must not be null");
		synchronized (this.aliasMap) {
			Map<String, String> aliasCopy = new HashMap<>(this.aliasMap);
			aliasCopy.forEach((alias, registeredName) -> {
				String resolvedAlias = valueResolver.resolveStringValue(alias);
				String resolvedName = valueResolver.resolveStringValue(registeredName);
				//如果别名和规范名称解析后为null或者相等，则从字典中删除
				if (resolvedAlias == null || resolvedName == null || resolvedAlias.equals(resolvedName)) {
					this.aliasMap.remove(alias);
				}
				//如果解析后的别名和原始别名不登
				else if (!resolvedAlias.equals(alias)) {
					//获取字典中的规范名称
					String existingName = this.aliasMap.get(resolvedAlias);
					//如果解析后的规范名臣个解析后的别名在字典中对应的规范名称相等，则删除字典中alias
					if (existingName != null) {
						if (existingName.equals(resolvedName)) {
							// Pointing to existing alias - just remove placeholder
							this.aliasMap.remove(alias);
							return;
						}
						throw new IllegalStateException(
								"Cannot register resolved alias '" + resolvedAlias + "' (original: '" + alias +
								"') for name '" + resolvedName + "': It is already registered for name '" +
								registeredName + "'.");
					}
					//检测给定的名称是否已经作为另一个方向的别名指向给定的别名，并预先捕获循环引用
					checkForAliasCircle(resolvedName, resolvedAlias);
					//删除字典别名
					this.aliasMap.remove(alias);
					//新增字典
					this.aliasMap.put(resolvedAlias, resolvedName);
				}
				else if (!registeredName.equals(resolvedName)) {
					this.aliasMap.put(alias, resolvedName);
				}
			});
		}
	}

	/**
	 * Check whether the given name points back to the given alias as an alias
	 * in the other direction already, catching a circular reference upfront
	 * and throwing a corresponding IllegalStateException.
	 * 检测给定的名称是否已经作为另一个方向的别名指向给定的别名，并预先捕获循环引用
	 * @param name the candidate name
	 * @param alias the candidate alias
	 * @see #registerAlias
	 * @see #hasAlias
	 */
	protected void checkForAliasCircle(String name, String alias) {
		if (hasAlias(alias, name)) {
			throw new IllegalStateException("Cannot register alias '" + alias +
					"' for name '" + name + "': Circular reference - '" +
					name + "' is a direct or indirect alias for '" + alias + "' already");
		}
	}

	/**
	 * Determine the raw name, resolving aliases to canonical names.
	 * 循环处理，从aliasMap中根据aliasName获取真实beanName，直到获取到的真实beanName为null
	 * @param name the user-specified name
	 * @return the transformed name
	 */
	public String canonicalName(String name) {
		String canonicalName = name;
		// Handle aliasing...
		String resolvedName;
		do {
			//从字典中获取别名对应的规范名称
			resolvedName = this.aliasMap.get(canonicalName);
			if (resolvedName != null) {
				canonicalName = resolvedName;
			}
		}
		while (resolvedName != null);
		return canonicalName;
	}

}
