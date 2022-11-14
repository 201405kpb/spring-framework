package com.kpb.spring.model;

import org.springframework.beans.factory.FactoryBean;

public class TestFactoryBean implements FactoryBean<TestBean> {
	@Override
	public TestBean getObject() throws Exception {
		return new TestBean();
	}

	@Override
	public Class<?> getObjectType() {
		return TestBean.class;
	}
}
