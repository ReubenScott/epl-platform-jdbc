package com.soak.framework.jdbc.mssql;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import junit.framework.TestCase;

import com.soak.framework.jdbc.TestUtil;
import com.soak.jdbcframe.jdbc.core.RowMapper;

/**
 * <p>
 * Title:
 * </p>
 * 
 * <p>
 * Description:
 * </p>
 * 
 * <p>
 * Copyright: Copyright (c) 2007
 * </p>
 * 
 * @author ���ڼ�
 * @main sunyujia@yahoo.cn
 * @date May 17, 2008 10:05:56 PM
 */
public class SqlTest extends TestCase {

  public void test0() {
    String sql = "select 'syj1' as id union all select 'syj2' as id";
    List list = TestUtil.getMssqlJt().executeQuery(sql);
    List list1 = TestUtil.getMssqlJt().executeQuery(sql, new RowMapper() {
      public Object mapRow(ResultSet rs, int rowNum) throws SQLException {
        return rs.getObject(1);
      }
    });
  }
}
