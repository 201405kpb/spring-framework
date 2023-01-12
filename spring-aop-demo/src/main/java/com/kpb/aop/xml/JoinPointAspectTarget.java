package com.kpb.aop.xml;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JoinPointAspectTarget {

	public List<Integer> target(String str, Date date) {
		System.out.println("_____target_____");
		return Stream.of(1,2,3).collect(Collectors.toList());
	}
}
