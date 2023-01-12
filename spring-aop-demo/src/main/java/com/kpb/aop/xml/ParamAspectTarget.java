package com.kpb.aop.xml;

import java.util.Date;

public class ParamAspectTarget {
	public int target(int i, Date date, String string) {
		//构造一个异常
		//int y=1/0;

		return i;
	}
}
