package com.kpb.aop.annotation;

import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.DeclareParents;
import org.springframework.stereotype.Component;

@Component
@Aspect
public class AspectIntroduction {

	/**
	 * value: 需要增强的的类扫描路径，该路径下的被Spring管理的bean都将被增强
	 * DeclareParents绑定的属性所属类型AddFunction: AddFunction
	 * defaultImpl: 增强接口的方法的默认实现
	 */
	@DeclareParents(value = "*..BasicFunction", defaultImpl = AddFunctionImpl.class)
	public static AddFunction addFunction;
}
