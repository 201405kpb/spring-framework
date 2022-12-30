/*
 * Copyright 2002-2017 the original author or authors.
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

package org.springframework.core.type;

import org.springframework.lang.Nullable;

/**
 * Interface that defines abstract metadata of a specific class,
 * in a form that does not require that class to be loaded yet.
 *
 * 类元数据接口
 *
 * @author Juergen Hoeller
 * @since 2.5
 * @see StandardClassMetadata
 * @see org.springframework.core.type.classreading.MetadataReader#getClassMetadata()
 * @see AnnotationMetadata
 *
 * Top Level Class：顶层类，即普通类
 * Inner Class：非静态内部类
 * Nested Class：嵌套类（静态内部类）
 * Local Class：方法内声明的局部类
 * Anonymous Class：匿名类
 *
 */
public interface ClassMetadata {

	/**
	 * Return the name of the underlying class.
	 *  返回基础类的名称
	 */
	String getClassName();

	/**
	 * Return whether the underlying class represents an interface.
	 * 是否为接口
	 */
	boolean isInterface();

	/**
	 * Return whether the underlying class represents an annotation.
	 * 是否为标注类
	 * @since 4.1
	 */
	boolean isAnnotation();

	/**
	 * Return whether the underlying class is marked as abstract.
	 * 返回是否为抽象类
	 */
	boolean isAbstract();

	/**
	 * Return whether the underlying class represents a concrete class,
	 * i.e. neither an interface nor an abstract class.
	 * 是否一个具体的类，即不是接口或者抽象类，换句话说，可 new
	 */
	default boolean isConcrete() {
		return !(isInterface() || isAbstract());
	}

	/**
	 * Return whether the underlying class is marked as 'final'.
	 * 是否为 final
	 */
	boolean isFinal();

	/**
	 * Determine whether the underlying class is independent, i.e. whether
	 * it is a top-level class or a nested class (static inner class) that
	 * can be constructed independently of an enclosing class.
	 *
	 * 确定基础类是否独立，即它是顶级类还是可以独立于封闭类构建的嵌套类（静态内部类）。
	 *
	 */
	boolean isIndependent();

	/**
	 * Return whether the underlying class is declared within an enclosing
	 * class (i.e. the underlying class is an inner/nested class or a
	 * local class within a method).
	 * 基础类是在封闭类内声明的（即基础类是内部嵌套类还是方法内的局部类）。
	 * <p>If this method returns {@code false}, then the underlying
	 * class is a top-level class.
	 * 如果此方法返回false，则基础类是顶级类。
	 */
	default boolean hasEnclosingClass() {
		return (getEnclosingClassName() != null);
	}

	/**
	 * Return the name of the enclosing class of the underlying class,
	 * or {@code null} if the underlying class is a top-level class.
	 * 基础类的封闭类的名称，如果基础类是顶级类，则返回 null
	 */
	@Nullable
	String getEnclosingClassName();

	/**
	 * Return whether the underlying class has a superclass.
	 * 基础类是否具有超类
	 */
	default boolean hasSuperClass() {
		return (getSuperClassName() != null);
	}

	/**
	 * Return the name of the superclass of the underlying class,
	 * or {@code null} if there is no superclass defined.
	 * 基础类的超类的名称，如果没有定义超类，则返回 null
	 */
	@Nullable
	String getSuperClassName();

	/**
	 * Return the names of all interfaces that the underlying class
	 * implements, or an empty array if there are none.
	 * 返回基础类实现的所有接口的名称，如果没有，则返回空数组。
	 */
	String[] getInterfaceNames();

	/**
	 * Return the names of all classes declared as members of the class represented by
	 * this ClassMetadata object. This includes public, protected, default (package)
	 * access, and private classes and interfaces declared by the class, but excludes
	 * inherited classes and interfaces. An empty array is returned if no member classes
	 * or interfaces exist.
	 * 返回所有（继承、实现）该类的 成员类（内部类、接口除外）
	 * @since 3.1
	 */
	String[] getMemberClassNames();

}
