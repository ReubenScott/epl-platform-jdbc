package com.soak.framework.jdbc.core;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Vector;

import org.apache.poi.hssf.usermodel.HSSFDataFormat;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import au.com.bytecode.opencsv.CSVReader;
import au.com.bytecode.opencsv.CSVWriter;

import com.soak.common.constant.CharSetType;
import com.soak.common.constant.DateBaseType;
import com.soak.common.date.DateStyle;
import com.soak.common.date.DateUtil;
import com.soak.common.io.ExcelUtil;
import com.soak.common.io.IOHandler;
import com.soak.common.io.PropertyReader;
import com.soak.common.util.BeanUtil;
import com.soak.common.util.StringUtil;
import com.soak.framework.jdbc.Restrictions;
import com.soak.framework.jdbc.context.JdbcConfig; //import com.soak.framework.orm.Column;
//import com.soak.framework.orm.Table;

import javax.persistence.Column;
import javax.persistence.Table;
import javax.sql.DataSource;
import org.apache.commons.dbcp.BasicDataSource;

/**
 * Jdbc 模版
 * 
 */
public abstract class JdbcTemplate {
  protected static final Logger logger = LoggerFactory.getLogger(JdbcTemplate.class);

  private volatile static JdbcTemplate instance;
  private static DataSource dataSource;
  private final static JdbcConfig dbParameter = new JdbcConfig();
  // Jdbc Template mapper
  private static Map<String, Class<? extends JdbcTemplate>> mapper = new HashMap<String, Class<? extends JdbcTemplate>>();

  protected final int BATCHCOUNT = 5000; // 批量提交记录数

  static {
    mapper.put("DB2", DB2Template.class); // DB2/LINUXX8664
    mapper.put("PostgreSQL", PostgreSQLTemplate.class);
  }

  // 根据数据库类型，创建相应的JdbcTemplate
  public synchronized static JdbcTemplate getInstance() {
    Connection connection = null;
    String productName = null;
    if (instance == null) {
      Properties ps = PropertyReader.getInstance().read("jdbc.properties");
      String drivername = ps.getProperty("jdbc.driverClassName");
      String url = ps.getProperty("jdbc.url");
      String username = ps.getProperty("jdbc.username");
      String password = ps.getProperty("jdbc.password");

      dbParameter.setDriverclass(ps.getProperty("jdbc.driverClassName"));
      dbParameter.setUrl(ps.getProperty("jdbc.url"));
      dbParameter.setUsername(ps.getProperty("jdbc.username"));
      dbParameter.setPassword(ps.getProperty("jdbc.password"));
      try {
        Class.forName(dbParameter.getDriverclass());
        connection = DriverManager.getConnection(dbParameter.getUrl(), dbParameter.getUsername(), dbParameter.getPassword());

        DatabaseMetaData metaData = connection.getMetaData();
        productName = metaData.getDatabaseProductName();
        DateBaseType dbType = DateBaseType.getDateBaseType(productName);
        instance = mapper.get(dbType.getValue()).newInstance(); // 创建对象

        // TODO 初始化连接池 目前测试出 有些 连接池版本不稳定
        // initPool();

      } catch (Exception e) {
        e.printStackTrace();
        logger.error("getInstance() Exception: {}", e.toString());
        throw new RuntimeException("无法创建【" + productName + "】对应的JDBC模版");
      } finally {
        if (connection != null) {
          try {
            connection.close();
          } catch (SQLException e) {
            e.printStackTrace();
          }
        }
      }
    }
    return instance;
  }

  /**
   * 
   * 初始化数据库连接池
   */
  private static void initPool(JdbcConfig dbParameter) {
    BasicDataSource dbcpDataSource = new BasicDataSource();
    dbcpDataSource.setDriverClassName(dbParameter.getDriverclass());
    dbcpDataSource.setUrl(dbParameter.getUrl());
    dbcpDataSource.setUsername(dbParameter.getUsername());
    dbcpDataSource.setPassword(dbParameter.getPassword());

    // dbcpDataSource.setInitialSize(Integer.parseInt(ps.getProperty("pool.initialSize")));
    // dbcpDataSource.setMaxActive(Integer.parseInt(ps.getProperty("pool.maxActive")));
    // dbcpDataSource.setMaxIdle(Integer.parseInt(ps.getProperty("pool.maxIdle")));
    dataSource = dbcpDataSource;
  }

  /**
   * 获取 数据库连接
   * 
   * @return
   */
  protected Connection getConnection() {
    Connection connection = null;
    try {
      // connection = dataSource.getConnection();
      connection = DriverManager.getConnection(dbParameter.getUrl(), dbParameter.getUsername(), dbParameter.getPassword());
      // connection.setCatalog(catalog)
    } catch (SQLException e) {
      e.printStackTrace();
      logger.error("getConnection() Exception: {}", e.toString());
      logger.error("getConnection() Exception: {}", e.getNextException().toString());
    } finally {
      // checkIn(connection);
    }

    return connection;
  }

  /**
   * 获取数据库 类型
   */
  public DateBaseType getDBProductType() {
    DateBaseType dbType = null;
    try {
      Connection connection = this.getConnection();
      DatabaseMetaData metaData = connection.getMetaData();
      String productName = metaData.getDatabaseProductName();
      dbType = DateBaseType.getDateBaseType(productName);
    } catch (SQLException e) {
      e.printStackTrace();
    }
    return dbType;
  }

  /**
   * Close the given JDBC Connection and ignore any thrown exception. This is
   * useful for typical finally blocks in manual JDBC code.
   * 
   * @param con
   *          the JDBC Connection to close (may be <code>null</code>)
   */
  public static void closeConnection(Connection con) {
    if (con != null) {
      try {
        con.close();
      } catch (SQLException ex) {
        logger.debug("Could not close JDBC Connection", ex);
      } catch (Throwable ex) {
        // We don't trust the JDBC driver: It might throw
        // RuntimeException or Error.
        logger.debug("Unexpected exception on closing JDBC Connection", ex);
      }
    }
  }

  /**
   * Close the given JDBC Statement and ignore any thrown exception. This is
   * useful for typical finally blocks in manual JDBC code.
   * 
   * @param stmt
   *          the JDBC Statement to close (may be <code>null</code>)
   */
  public static void closeStatement(Statement stmt) {
    if (stmt != null) {
      try {
        stmt.close();
      } catch (SQLException ex) {
        logger.debug("Could not close JDBC Statement", ex);
      } catch (Throwable ex) {
        // We don't trust the JDBC driver: It might throw
        // RuntimeException or Error.
        logger.debug("Unexpected exception on closing JDBC Statement", ex);
      }
    }
  }

  /**
   * Close the given JDBC ResultSet and ignore any thrown exception. This is
   * useful for typical finally blocks in manual JDBC code.
   * 
   * @param rs
   *          the JDBC ResultSet to close (may be <code>null</code>)
   */
  public static void closeResultSet(ResultSet rs) {
    if (rs != null) {
      try {
        rs.close();
      } catch (SQLException ex) {
        logger.debug("Could not close JDBC ResultSet", ex);
      } catch (Throwable ex) {
        // We don't trust the JDBC driver: It might throw
        // RuntimeException or Error.
        logger.debug("Unexpected exception on closing JDBC ResultSet", ex);
      }
    }
  }

  /**
   * 关闭数据库连接
   * 
   * @param conn
   * @param st
   * @param rs
   */
  protected void release(Connection connection, Statement st, ResultSet rs) {
    try {
      if (rs != null) {
        rs.close();
        rs = null;
      }
      if (st != null) {
        st.close();
        st = null;
      }
      if (connection != null) {
        connection.close();
        connection = null;
      }
    } catch (SQLException e) {
      logger.info(e.getSQLState());
    } finally {
      if (connection != null) {
        try {
          connection.close();
        } catch (SQLException e) {
          e.printStackTrace();
        }
      }

    }
  }

  /**
   * 设置参数
   * 
   * @param ps
   * @param params
   */
  protected void setPreparedValues(PreparedStatement ps, List<Object> params) {
    try {
      if (params != null && params.size() > 0) {
        for (int i = 0; i < params.size(); i++) {
          // ps.setObject(i + 1, params.get(i));
          ps.setObject(i + 1, castJavatoDBValue(params.get(i)));
        }
      }
    } catch (SQLException e) {
      e.printStackTrace();
    }
  }

  /**
   * 设置参数
   * 
   * @param ps
   * @param params
   */
  protected void setPreparedValues(PreparedStatement ps, Object[] params) {
    try {
      if (params != null && params.length > 0) {
        for (int i = 0; i < params.length; i++) {
          // ps.setObject(i + 1, params[i]);
          ps.setObject(i + 1, castJavatoDBValue(params[i]));
        }
      }
    } catch (SQLException e) {
      e.printStackTrace();
    }
  }

  /**
   * Java 转数据库 类型
   * 
   * @param dbColumnType
   * @param value
   * @return
   */
  private Object castJavatoDBValue(Object javaObj) {
    if (javaObj instanceof java.util.Date) {
      javaObj = new java.sql.Date(((java.util.Date) javaObj).getTime());
    }
    return javaObj;
  }

  public static void setParameters(PreparedStatement pstmt, List parameters) throws SQLException {
    for (int i = 1, size = parameters.size(); i <= size; i++) {
      Object value = parameters.get(i - 1);
      if (value instanceof String) {
        pstmt.setString(i, (String) value);
      } else if (value instanceof Integer) {
        pstmt.setInt(i, ((Integer) value).intValue());
      } else if (value instanceof Long) {
        pstmt.setLong(i, ((Long) value).longValue());
      } else if (value instanceof Double) {
        pstmt.setDouble(i, ((Double) value).doubleValue());
      } else if (value instanceof Float) {
        pstmt.setFloat(i, ((Float) value).floatValue());
      } else if (value instanceof Short) {
        pstmt.setShort(i, ((Short) value).shortValue());
      } else if (value instanceof Byte) {
        pstmt.setByte(i, ((Byte) value).byteValue());
      } else if (value instanceof BigDecimal) {
        pstmt.setBigDecimal(i, (BigDecimal) value);
      } else if (value instanceof Boolean) {
        pstmt.setBoolean(i, ((Boolean) value).booleanValue());
      } else if (value instanceof Timestamp) {
        pstmt.setTimestamp(i, (Timestamp) value);
      } else if (value instanceof java.util.Date) {
        pstmt.setDate(i, new java.sql.Date(((java.util.Date) value).getTime()));
      } else if (value instanceof java.sql.Date) {
        pstmt.setDate(i, (java.sql.Date) value);
      } else if (value instanceof Time) {
        pstmt.setTime(i, (Time) value);
      } else if (value instanceof Blob) {
        pstmt.setBlob(i, (Blob) value);
      } else if (value instanceof Clob) {
        pstmt.setClob(i, (Clob) value);
      } else {
        pstmt.setObject(i, value);
      }
    }
  }

  /**
   * 根据数据库 字段类型 返回值
   * 
   * @param dbColumnType
   * @param value
   * @return
   */
  protected Object castDBType(int dbColumnType, String value) {
    if (value == null) {
      return null;
    }
    Object result = null;
    switch (dbColumnType) {
      case Types.CHAR:
      case Types.VARCHAR:
      case Types.LONGVARCHAR:
        result = value;
        break;
      case Types.BIT:
      case Types.TINYINT:
      case Types.SMALLINT:
      case Types.INTEGER:
      case Types.BIGINT:
        value = value.trim();
        if (value.equals("")) {
          result = 0;
        } else {
          double d = Double.valueOf(value.trim()).doubleValue();
          result = new Long(new DecimalFormat("#").format(d));
        }
        break;
      case Types.DOUBLE:
      case Types.FLOAT:
      case Types.REAL:
        value = value.trim();
        if (value.equals("")) {
          result = 0;
        } else {
          result = Double.valueOf(value);
        }
        break;
      case Types.NUMERIC:
      case Types.DECIMAL:
        value = value.trim();
        result = new BigDecimal(value.equals("") ? "0" : value);
        break;
      case Types.DATE: // 2016-2-25
        result = DateUtil.parseShortDate(value);
        break;
      case Types.TIMESTAMP: // 2016-2-25 7:41:18 时间戳
        result = DateUtil.parseDateTime(value);
        break;
      case Types.TIME:
        result = DateUtil.parseShortTime(value);
        break;
      default:
        logger.error("JdbcTemplate castDBType()  lost Data  type : " + dbColumnType);
    }

    return result;
  }

  /***
   * 
   * 获得当前的Schema
   */
  public abstract String getCurrentSchema();

  /***
   * 获取表字段 类型信息
   * 
   */
  protected abstract List<Integer> getColumnTypes(String schema, String tablename);

  /***
   * 
   * 清空表
   */
  public abstract boolean truncateTable(String schema, String tablename);

  /***
   * 判断数据库 表 是否存在
   * 
   * @param schema
   * @param tableName
   * @return
   */
  public abstract boolean isTableExits(String schema, String tableName);

  /**
   * 带参数的翻页功能(oracle)
   * 
   * @param sql
   *          String
   * @param paramList
   *          ArrayList
   * @param startIndex
   *          int
   * @param size
   *          int
   * @return HashMap[]
   */
  public HashMap[] queryPageSQL(String sql, int startIndex, int size, Object... paramList) {
    StringBuffer querySQL = new StringBuffer();
    querySQL.append("select * from (select my_table.*,rownum as my_rownum from(").append(sql).append(") my_table where rownum<").append(startIndex + size).append(
        ") where my_rownum>=").append(startIndex);

    return queryForMap(querySQL.toString(), paramList);
  }

  /**
   * 删除表
   * 
   * @param schema
   * @param tableName
   * @return
   */
  public boolean dropTable(String schema, String tableName) {
    schema = StringUtil.isEmpty(schema) ? null : schema.toUpperCase();
    if (isTableExits(schema, tableName)) {
      this.truncateTable(schema, tableName);
      if (StringUtil.isEmpty(schema)) {
        this.execute("Drop table " + tableName);
      } else {
        this.execute("Drop table " + schema + "." + tableName);
      }
    }
    return !isTableExits(schema, tableName);
  }

  /**
   * 清空表
   * 
   * @param entityClass
   * @return
   */
  public boolean truncateAnnotatedTable(Class<? extends Object> entityClass) {
    if (entityClass.isAnnotationPresent(Table.class)) { // 如果类映射了表
      Table table = (Table) entityClass.getAnnotation(Table.class);
      String schema = table.schema();
      String tablename = table.name();
      return truncateTable(schema, tablename);
    }

    return false;
  }

  /**
   * 
   * 根据实例更新
   * 
   */
  public boolean updateAnnotatedEntity(Object annotatedSample, List<Restrictions> restrictions) {
    return updateAnnotatedEntity(annotatedSample, restrictions.toArray(new Restrictions[restrictions.size()]));
  }

  /**
   * 
   * 根据实例更新
   * 
   */
  public boolean updateAnnotatedEntity(Object annotatedSample, Restrictions... restrictions) {
    boolean result = false;
    List<String> fieldNames = new ArrayList<String>();
    List<Object> params = new ArrayList<Object>();
    String schema = null;
    String tablename = null;
    StringBuffer sql = new StringBuffer("update ");

    // 获取类的class
    Class<? extends Object> stuClass = annotatedSample.getClass();

    // 通过获取类的类注解，来获取类映射的表名称
    if (stuClass.isAnnotationPresent(Table.class)) { // 如果类映射了表
      Table table = (Table) stuClass.getAnnotation(Table.class);
      schema = table.schema();
      tablename = table.name();

      if (!StringUtil.isEmpty(schema)) {
        sql.append(schema + ".");
      }
      sql.append(tablename + " set ");

      // 遍历所有的字段
      Field[] fields = stuClass.getDeclaredFields();// 获取类的字段信息
      for (Field field : fields) {
        if (field.isAnnotationPresent(Column.class)) {
          Column col = field.getAnnotation(Column.class); // 获取列注解
          String columnName = col.name(); // 数据库映射字段
          String fieldName = field.getName(); // 获取字段名称
          fieldNames.add(fieldName);
          String methodName = "get" + fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);// 获取字段的get方法

          try {
            // get到field的值
            Method method = stuClass.getMethod(methodName);
            Object fieldValue = method.invoke(annotatedSample);
            // 空字段跳过拼接过程。。。 // 如果没有值，不拼接
            if (fieldValue != null) {
              if (params.size() == 0) {
                sql.append(columnName + " = " + " ? ");
              } else {
                sql.append(", " + columnName + " = " + " ? ");
              }
              params.add(fieldValue);
            }
          } catch (SecurityException e) {
            e.printStackTrace();
          } catch (NoSuchMethodException e) {
            e.printStackTrace();
          } catch (IllegalArgumentException e) {
            e.printStackTrace();
          } catch (IllegalAccessException e) {
            e.printStackTrace();
          } catch (InvocationTargetException e) {
            e.printStackTrace();
          }

        }
      }
    }

    // 条件
    StringBuilder condition = new StringBuilder(" where 1=1 ");
    for (Restrictions term : restrictions) {
      condition.append(term.getSql());
      params.addAll(term.getParams());
    }
    sql.append(condition);

    logger.debug(sql.toString().replace("?", "?[{}]"), params.toArray());

    Connection conn = getConnection(); // this.getConnection(dbalias);
    PreparedStatement ps = null;
    try {
      ps = conn.prepareStatement(sql.toString());
      this.setPreparedValues(ps, params);
      ps.execute();
      result = true;
    } catch (SQLException e) {
      e.printStackTrace();
    } finally {
      this.release(conn, ps, null);
    }

    return result;
  }

  /**
   * 通过实体类生成 删除 语句
   * 
   * @param annotatedSample
   *          <a href="http://my.oschina.net/u/556800" class="referer"
   *          target="_blank">@return</a>
   * @throws IllegalArgumentException
   * @throws IllegalAccessException
   * @throws NumException
   */
  public boolean deleteAnnotatedBean(Object annotatedSample , Restrictions... restrictions) {
    List<String> columns = new ArrayList<String>();
    List<String> fieldNames = new ArrayList<String>();
    List<Object> params = new ArrayList<Object>();
    String schema = null;
    String tablename = null;
    List<Field> annoFields = new ArrayList<Field>();

    // 拼接 SQL 语句
    StringBuffer sql = new StringBuffer("delete from ");
    StringBuilder condition = new StringBuilder(" where 1=1 ");

    // 获取类的class
    Class<? extends Object> stuClass = annotatedSample.getClass();
    if (annotatedSample != null) {
      if (stuClass.isAnnotationPresent(Table.class)) { // 获得类是否有注解
        Table table = stuClass.getAnnotation(Table.class);
        schema = table.schema().trim(); // 获得schema
        tablename = table.name().trim(); // 获得表名

        // 拼接 SQL 语句
        if (StringUtil.isEmpty(schema)) {
          sql.append(tablename);
        } else {
          sql.append(schema.trim() + "." + tablename);
        }

        Field[] fields = stuClass.getDeclaredFields();// 获得反射对象集合
        for (Field field : fields) {// 循环组装 field : fields
          if (field.isAnnotationPresent(Column.class)) {
            Column col = field.getAnnotation(Column.class); // 获取列注解
            String columnName = col.name(); // 数据库映射字段
            if (StringUtil.isEmpty(columnName)) { // name 未指定 ，设置默认 为 字段名
              columnName = field.getName();
            }
            columns.add(columnName);
            String fieldName = field.getName(); // 获取字段名称
            fieldNames.add(fieldName);
            String methodName = "get" + fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);// 获取字段的get方法

            try {
              // get到field的值
              Method method = stuClass.getMethod(methodName);
              Object fieldValue = method.invoke(annotatedSample);
              // params[i] = method.invoke(annotatedSample);
              // 空字段跳过拼接过程。。。 
              if (fieldValue != null) {
                condition.append(" and " + columnName + "=" + "?");
                params.add(fieldValue);
              } else { // 如果没有值，不拼接
                condition.append(" and " + columnName + " IS NULL ");
              }
            } catch (IllegalArgumentException e) {
              e.printStackTrace();
            } catch (IllegalAccessException e) {
              e.printStackTrace();
            } catch (SecurityException e) {
              e.printStackTrace();
            } catch (NoSuchMethodException e) {
              e.printStackTrace();
            } catch (InvocationTargetException e) {
              e.printStackTrace();
            }

          }
        }
        sql.append(condition);
      }
    }

    logger.debug(sql.toString());

    return execute(sql.toString(), params);
  }

  /***
   * 
   * 执行DDL 语句
   */
  public boolean execute(String sql, Object... params) {
    boolean result = false;
    Connection conn = getConnection();
    PreparedStatement ps = null;
    try {
      ps = conn.prepareStatement(sql);
      this.setPreparedValues(ps, params);
      ps.execute();
      result = true;
    } catch (SQLException e) {
      e.printStackTrace();
      try {
        conn.rollback();
      } catch (SQLException ex) {
        ex.printStackTrace();
      }
      logger.error(sql);
    } finally {
      this.release(conn, ps, null);
    }
    return result;
  }

  /**
   * 执行一批sql
   * 
   * @param sqlList
   *          List
   * @return boolean
   */
  public void executeBatch(List<String> sqls) {
    Connection conn = getConnection();
    Statement st = null;
    try {
      conn.setAutoCommit(false);
      st = conn.createStatement();
      for (int i = 0; i < sqls.size(); i++) {
        String sql = sqls.get(i);
        st.addBatch(sql);
      }
      st.executeBatch();
      conn.commit();
      conn.setAutoCommit(true);
    } catch (SQLException e) {
      try {
        conn.rollback();
      } catch (SQLException ex) {
        ex.printStackTrace();
      }
      e.printStackTrace();
    } finally {
      this.release(conn, st, null);
    }
  }

  /**
   * 执行一条带参数的sql
   * 
   * @param sql
   *          String
   * @param param
   *          List
   * @return 影响行数
   */
  public int executeUpdate(String sql, Object... params) {
    int count = 0;
    Connection conn = getConnection();
    PreparedStatement ps = null;
    try {
      ps = conn.prepareStatement(sql);
      this.setPreparedValues(ps, params);
      count = ps.executeUpdate();
    } catch (SQLException e) {
      e.printStackTrace();
      try {
        conn.rollback();
      } catch (SQLException ex) {
        ex.printStackTrace();
      }
      logger.error(sql);
    } finally {
      this.release(conn, ps, null);
    }
    return count;
  }

  /**
   * 一条带参数的sql 执行多次
   * 
   * @param sql
   *          String
   * @param param
   *          List Object[] 的集合
   * @return boolean
   */
  public boolean executeBatch(String sql, List<Object[]> arrays) {
    boolean result = false;
    Connection connection = getConnection();
    PreparedStatement ps = null;
    int loadCount = 0; // 批量计数
    try {
      connection.setAutoCommit(false);
      ps = connection.prepareStatement(sql);
      for (int i = 0; i < arrays.size(); i++) {
        Object[] params = arrays.get(i);
        this.setPreparedValues(ps, params);
        ps.addBatch();
        if (++loadCount % BATCHCOUNT == 0) {
          ps.executeBatch();
          connection.commit();
        }
      }
      ps.executeBatch();
      connection.commit();
      connection.setAutoCommit(true);
      result = true;
    } catch (SQLException e) {
      e.printStackTrace();
      logger.error("executeBatch Exception: {}", e.toString());
      logger.error("executeBatch Exception: {}", e.getNextException().toString());
      try {
        connection.rollback();
      } catch (SQLException ex) {
        ex.printStackTrace();
      }
      return false;
    } finally {
      this.release(connection, ps, null);
    }
    return result;
  }

  /**
   * 通过实体类生成 insert into sql语句
   * 
   * @param annoBean
   *          <a href="http://my.oschina.net/u/556800" class="referer"
   *          target="_blank">@return</a>
   * @throws IllegalArgumentException
   * @throws IllegalAccessException
   * @throws NumException
   */
  public boolean saveAnnotatedBean(Object... annoBeans) {
    List<String> columns = new ArrayList<String>();
    List<Object[]> paramList = new ArrayList<Object[]>();
    StringBuilder values = new StringBuilder();
    String schema = null;
    String tablename = null;
    Class<? extends Object> stuClass = null;
    List<Field> annoFields = new ArrayList<Field>();

    List beans = new ArrayList();

    // 判断是不是集合 将 参数 annoBeans（数组 或 集合） 转为 集合，方便下面便利处理。
    for (Object annoBean : annoBeans) {
      if (annoBean instanceof Collection) {
        beans.addAll((Collection) annoBean);
      } else {
        beans.add(annoBean);
      }
    }

    // SQL 参数
    for (Object annoBean : beans) {
      // 获取类的class
      stuClass = annoBean.getClass();
      if (annoBean != null) {
        if (stuClass.isAnnotationPresent(Table.class)) { // 获得类是否有注解
          Table table = stuClass.getAnnotation(Table.class);
          schema = table.schema().trim(); // 获得schema
          tablename = table.name().trim(); // 获得表名
          Field[] fields = stuClass.getDeclaredFields();// 获得反射对象集合
          for (Field field : fields) {// 循环组装 field : fields
            if (field.isAnnotationPresent(Column.class)) {
              annoFields.add(field);
              Column col = field.getAnnotation(Column.class); // 获取列注解
              String columnName = col.name(); // 数据库映射字段
              if (StringUtil.isEmpty(columnName)) { // name 未指定 ，设置默认 为 字段名
                columnName = field.getName();
              }
              if (columns.size() == 0) {
                values.append("?");
              } else {
                values.append(" , ?");
              }
              columns.add(columnName);
            }
          }
        }
        break;
      }
    }

    // SQL 参数值
    for (Object annoBean : beans) {
      if (annoBean != null) {
        Object[] params = new Object[annoFields.size()];
        for (int i = 0; i < annoFields.size(); i++) {// 循环组装 field : fields
          Field field = annoFields.get(i);
          String fieldName = field.getName(); // 获取字段名称
          String methodName = "get" + fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);// 获取字段的get方法
          try {
            // get到field的值
            Method method = stuClass.getMethod(methodName);
            params[i] = method.invoke(annoBean);
          } catch (IllegalArgumentException e) {
            e.printStackTrace();
          } catch (IllegalAccessException e) {
            e.printStackTrace();
          } catch (SecurityException e) {
            e.printStackTrace();
          } catch (NoSuchMethodException e) {
            e.printStackTrace();
          } catch (InvocationTargetException e) {
            e.printStackTrace();
          }
        }

        paramList.add(params);
      }
    }

    // 拼接 SQL 语句
    // String sql = "insert into " + tablename + "(" +
    // StringUtil.arrayToString(columns) + ") values (" + values + ")";
    StringBuffer sql = new StringBuffer("insert into ");
    if (StringUtil.isEmpty(schema)) {
      sql.append(tablename);
    } else {
      sql.append(schema.trim() + "." + tablename);
    }
    sql.append(" (" + StringUtil.arrayToString(columns) + ") values (" + values + ")");

    return executeBatch(sql.toString(), paramList);
  }

  /**
   * 调用存储过程
   * 
   * out : Types.INTEGER
   */
  public List callProcedure(String procedureName, Object[] in, int... outTypes) {
    List result = new ArrayList();
    StringBuffer sql;
    sql = new StringBuffer("{call " + procedureName + "(");
    if (null == in || in.length == 0) { // 无入参
      // 有出参
      if (outTypes != null && outTypes.length > 0) {
        for (int i = 1; i < outTypes.length; i++) {
          sql.append("?,");
        }
        sql.append("?");
      }
    } else { // 有入参
      for (int i = 1; i < in.length; i++) {
        sql.append("?,");
      }
      sql.append("?");
      // 有出参
      if (outTypes != null && outTypes.length > 0) {
        for (int i = 0; i < outTypes.length; i++) {
          sql.append(",?");
        }
      }
    }
    sql.append(")}");

    logger.debug(sql.toString().replace("?", "?[{}]"), in);

    // 获取数据库连接
    Connection conn = getConnection();
    // 使用Connection来创建一个CallableStatment对象
    CallableStatement cstmt = null;
    try {
      cstmt = conn.prepareCall(sql.toString());
      int parameterIndex = 0;
      // 注册CallableStatement 入参数是int类型
      if (in != null && in.length > 0) {
        for (int i = 0; i < in.length; i++) {
          cstmt.setObject(++parameterIndex, in[i]);
        }
      }
      int startIndex = parameterIndex + 1; // 出参起始 位置序号
      // 注册CallableStatement的第三个参数是int类型
      for (int type : outTypes) {
        cstmt.registerOutParameter(++parameterIndex, type);
      }
      // if( outTypes != null && outTypes.length > 0){
      // for (int i = 0; i < outTypes.length ; i++) {
      // cstmt.registerOutParameter(++parameterIndex, outTypes[i]);
      // }
      // }

      // 执行存储过程
      cstmt.execute();

      // 获取，并输出存储过程传出参数的值。
      for (int i = startIndex; i <= parameterIndex; i++) {
        result.add(cstmt.getObject(i));
      }
    } catch (SQLException e) {
      e.printStackTrace();
      return null;
    } finally {
      this.release(conn, cstmt, null);
    }

    return result;
  }

  /**
   * Retrieve a JDBC column value from a ResultSet, using the most appropriate
   * value type. The returned value should be a detached value object, not
   * having any ties to the active ResultSet: in particular, it should not be a
   * Blob or Clob object but rather a byte array respectively String
   * representation.
   * <p>
   * Uses the <code>getObject(index)</code> method, but includes additional "hacks" to get around Oracle 10g returning a non-standard object for its TIMESTAMP datatype and a
   * <code>java.sql.Date</code> for DATE columns leaving out the time portion: These columns will explicitly be extracted as standard <code>java.sql.Timestamp</code> object.
   * 
   * @param rs
   *          is the ResultSet holding the data
   * @param index
   *          is the column index
   * @return the value object
   * @throws SQLException
   *           if thrown by the JDBC API
   * @see java.sql.Blob
   * @see java.sql.Clob
   * @see java.sql.Timestamp
   */
  public static Object getResultSetValue(ResultSet rs, int index) throws SQLException {
    Object obj = rs.getObject(index);
    if (obj instanceof Blob) {
      obj = rs.getBytes(index);
    } else if (obj instanceof Clob) {
      obj = rs.getString(index);
    } else if (obj != null && obj.getClass().getName().startsWith("oracle.sql.TIMESTAMP")) {
      obj = rs.getTimestamp(index);
    } else if (obj != null && obj.getClass().getName().startsWith("oracle.sql.DATE")) {
      String metaDataClassName = rs.getMetaData().getColumnClassName(index);
      if ("java.sql.Timestamp".equals(metaDataClassName) || "oracle.sql.TIMESTAMP".equals(metaDataClassName)) {
        obj = rs.getTimestamp(index);
      } else {
        obj = rs.getDate(index);
      }
    } else if (obj != null && obj instanceof java.sql.Date) {
      if ("java.sql.Timestamp".equals(rs.getMetaData().getColumnClassName(index))) {
        obj = rs.getTimestamp(index);
      }
    }
    return obj;
  }

  public static Object getCallableStatementValue(CallableStatement cstm, int index) throws SQLException {
    Object obj = cstm.getObject(index);
    if (obj instanceof Blob) {
      obj = cstm.getBytes(index);
    } else if (obj instanceof Clob) {
      obj = cstm.getString(index);
    } else if (obj != null && obj.getClass().getName().startsWith("oracle.sql.TIMESTAMP")) {
      obj = cstm.getTimestamp(index);
    } else if (obj != null && obj.getClass().getName().startsWith("oracle.sql.DATE")) {
      String metaDataClassName = cstm.getMetaData().getColumnClassName(index);
      if ("java.sql.Timestamp".equals(metaDataClassName) || "oracle.sql.TIMESTAMP".equals(metaDataClassName)) {
        obj = cstm.getTimestamp(index);
      } else {
        obj = cstm.getDate(index);
      }
    } else if (obj != null && obj instanceof java.sql.Date) {
      if ("java.sql.Timestamp".equals(cstm.getMetaData().getColumnClassName(index))) {
        obj = cstm.getTimestamp(index);
      }
    }
    return obj;
  }

  public static Object getCallableStatementValue(CallableStatement cstm, String name) throws SQLException {
    Object obj = cstm.getObject(name);
    if (obj instanceof Blob) {
      obj = cstm.getBytes(name);
    } else if (obj instanceof Clob) {
      obj = cstm.getString(name);
    } else if (obj != null) {
      if (obj instanceof java.sql.Timestamp || "java.sql.Timestamp".equals(obj.getClass().getName())) {
        obj = cstm.getTimestamp(name);
      } else if (obj.getClass().getName().startsWith("oracle.sql.TIMESTAMP")) {
        obj = cstm.getTimestamp(name);
      } else if (obj instanceof java.sql.Date || "java.sql.Date".equals(obj.getClass().getName())) {
        obj = cstm.getDate(name);
      }
    }
    return obj;
  }

  public static Object getResultSetValue(ResultSet rs, String name) throws SQLException {
    Object obj = rs.getObject(name);
    if (obj instanceof Blob) {
      obj = rs.getBytes(name);
    } else if (obj instanceof Clob) {
      obj = rs.getString(name);
    } else if (obj != null) {
      if (obj instanceof java.sql.Timestamp || "java.sql.Timestamp".equals(obj.getClass().getName())) {
        obj = rs.getTimestamp(name);
      } else if (obj.getClass().getName().startsWith("oracle.sql.TIMESTAMP")) {
        obj = rs.getTimestamp(name);
      } else if (obj instanceof java.sql.Date || "java.sql.Date".equals(obj.getClass().getName())) {
        obj = rs.getDate(name);
      }
    }
    return obj;
  }

  /**
   * Return whether the given JDBC driver supports JDBC 2.0 batch updates.
   * <p>
   * Typically invoked right before execution of a given set of statements: to decide whether the set of SQL statements should be executed through the JDBC 2.0 batch mechanism or
   * simply in a traditional one-by-one fashion.
   * <p>
   * Logs a warning if the "supportsBatchUpdates" methods throws an exception and simply returns <code>false</code> in that case.
   * 
   * @param con
   *          the Connection to check
   * @return whether JDBC 2.0 batch updates are supported
   * @see java.sql.DatabaseMetaData#supportsBatchUpdates()
   */
  public static boolean supportsBatchUpdates(Connection con) {
    try {
      DatabaseMetaData dbmd = con.getMetaData();
      if (dbmd != null) {
        if (dbmd.supportsBatchUpdates()) {
          logger.debug("JDBC driver supports batch updates");
          return true;
        } else {
          logger.debug("JDBC driver does not support batch updates");
        }
      }
    } catch (SQLException ex) {
      logger.debug("JDBC driver 'supportsBatchUpdates' method threw exception", ex);
    } catch (AbstractMethodError err) {
      logger.debug("JDBC driver does not support JDBC 2.0 'supportsBatchUpdates' method", err);
    }
    return false;
  }

  /**
   * Check whether the given SQL type is numeric.
   * 
   * @param sqlType
   *          the SQL type to be checked
   * @return whether the type is numeric
   */
  public static boolean isNumeric(int sqlType) {
    return Types.BIT == sqlType || Types.BIGINT == sqlType || Types.DECIMAL == sqlType || Types.DOUBLE == sqlType || Types.FLOAT == sqlType || Types.INTEGER == sqlType
        || Types.NUMERIC == sqlType || Types.REAL == sqlType || Types.SMALLINT == sqlType || Types.TINYINT == sqlType;
  }

  /************************************************************ "select" start ************************************************************/

  /**
   * 查询一条记录
   * 
   * @param sql
   *          String
   * @param paramList
   *          ArrayList
   * @return HashMap
   */
  public Object queryOneObject(String sql, Object... params) {
    Connection conn = getConnection();
    PreparedStatement ps = null;
    ResultSet rs = null;
    try {
      // conn.setReadOnly(true);
      ps = conn.prepareStatement(sql);
      this.setPreparedValues(ps, params);
      rs = ps.executeQuery();
      rs.next();
      return rs.getObject(1);
    } catch (SQLException e) {
      logger.info(e.getMessage());
      return null;
    } finally {
      this.release(conn, ps, rs);
    }
  }

  /**
   * 查询一条记录
   * 
   * @param sql
   *          String
   * @param paramList
   *          ArrayList
   * @return HashMap
   */
  public List<?> queryOneAsList(String sql, Object... params) {
    Connection conn = getConnection();

    PreparedStatement ps = null;
    ResultSet rs = null;
    List row = new ArrayList();
    try {
      ps = conn.prepareStatement(sql);
      this.setPreparedValues(ps, params);
      rs = ps.executeQuery();
      ResultSetMetaData rsmd = rs.getMetaData();
      while (rs.next()) {
        for (int i = 1; i <= rsmd.getColumnCount(); i++) {
          row.add(rs.getObject(i));
        }
      }
      return row;
    } catch (SQLException e) {
      logger.info(e.getMessage());
      return null;
    } finally {
      this.release(conn, ps, rs);
    }
  }

  /**
   * 查询数据
   * 
   * @param sql
   *          String
   * @return HashMap[]
   */
  public HashMap[] queryForMap(String sql, Object... params) {
    Connection conn = getConnection();
    PreparedStatement ps = null;
    ResultSet rs = null;
    try {
      ps = conn.prepareStatement(sql);
      if (params != null) {
        for (int i = 0; i < params.length; i++) {
          ps.setObject(i + 1, params[i]);
        }
      }
      rs = ps.executeQuery();

      ResultSetMetaData rsmdt = rs.getMetaData();
      String[] colNames = new String[rsmdt.getColumnCount()];
      for (int i = 1; i <= rsmdt.getColumnCount(); i++) {
        colNames[i - 1] = rsmdt.getColumnName(i).toLowerCase();
      }
      Vector allRow = new Vector();
      while (rs.next()) {
        HashMap hashRow = new HashMap();
        for (int i = 0; i < colNames.length; i++) {
          hashRow.put(colNames[i], rs.getObject(colNames[i]));
        }
        allRow.add(hashRow);
      }
      HashMap[] HashAllRows = new HashMap[allRow.size()];
      for (int i = 0; i < HashAllRows.length; i++) {
        HashAllRows[i] = (HashMap) allRow.get(i);
      }
      return HashAllRows;
    } catch (SQLException e) {
      e.printStackTrace();
      logger.info(e.getMessage());
      try {
        conn.rollback();
      } catch (SQLException ex) {
        ex.printStackTrace();
      }
    } finally {
      this.release(conn, ps, rs);
    }
    return null;
  }

  /**
   * 查询sql执行的总记录数,带参数
   * 
   * @param sql
   *          String
   * @param paramList
   *          ArrayList
   * @return int
   */
  public int queryCountResult(String sql, Object... params) {
    Connection conn = getConnection();
    PreparedStatement ps = null;
    ResultSet rs = null;
    try {
      StringBuffer querySQL = new StringBuffer();
      querySQL.append("select count(1) from (").append(sql).append(") as my_table");
      ps = conn.prepareStatement(querySQL.toString());
      this.setPreparedValues(ps, params);
      rs = ps.executeQuery();
      if (rs.next()) {
        return rs.getInt(1);
      } else {
        return 0;
      }
    } catch (SQLException e) {
      logger.info(e.getMessage());
      logger.info(sql);
      return 0;
    } finally {
      this.release(conn, ps, rs);
    }
  }

  /**
   * 
   * sql 中 AS
   * 
   * 返回 封装 集合
   */
  public <T> List<T> querySampleList(Class<T> sample, String sql, Object... params) {
    Connection conn = getConnection();
    ResultSetMetaData rsmdt = null;
    PreparedStatement ps = null;
    ResultSet rs = null;
    List<T> list = new ArrayList<T>();
    try {
      // conn.setReadOnly(true);
      ps = conn.prepareStatement(sql);
      this.setPreparedValues(ps, params);
      rs = ps.executeQuery();
      rsmdt = rs.getMetaData();
      while (rs.next()) {
        Map<String, Object> map = new HashMap<String, Object>();
        for (int i = 1; i <= rsmdt.getColumnCount(); i++) {
          map.put(rsmdt.getColumnLabel(i), rs.getObject(i)); // 别称 sql 中 AS 后面的
        }
        list.add((T) BeanUtil.autoPackageBean(sample, map));
      }
    } catch (SQLException e) {
      e.printStackTrace();
      try {
        conn.rollback();
      } catch (SQLException ex) {
        ex.printStackTrace();
      }
    } finally {
      this.release(conn, ps, rs);
    }
    return list;
  }

  /**
   * 查询
   * 
   * @param sql
   * @return
   */
  public List<List> queryForList(String sql, Object... params) {
    Connection conn = getConnection();
    PreparedStatement ps = null;
    ResultSet rs = null;
    List<List> result = new ArrayList<List>();
    try {
      // conn.setReadOnly(true);
      ps = conn.prepareStatement(sql);
      this.setPreparedValues(ps, params);
      rs = ps.executeQuery();
      ResultSetMetaData rsmd = rs.getMetaData();
      while (rs.next()) {
        List row = new ArrayList();
        for (int i = 1; i <= rsmd.getColumnCount(); i++) {
          row.add(rs.getObject(i));
        }
        result.add(row);
      }
    } catch (SQLException e) {
      e.printStackTrace();
    } finally {
      this.release(conn, ps, rs);
    }
    return result;
  }

  /**
   * 根据模板查询 queryBySample
   * 
   */
  public <T> T findOneByAnnotatedSample(T annotatedSample, Restrictions... restrictions) {
    List<String> columns = new ArrayList<String>();
    List<String> fieldNames = new ArrayList<String>();
    List<Object> params = new ArrayList<Object>();
    StringBuilder condition = new StringBuilder(" where 1=1 ");
    String schema = null;
    String tablename = null;

    // 获取类的class
    Class<? extends Object> stuClass = annotatedSample.getClass();

    // 通过获取类的类注解，来获取类映射的表名称
    if (stuClass.isAnnotationPresent(Table.class)) { // 如果类映射了表
      Table table = (Table) stuClass.getAnnotation(Table.class);
      // sb.append(table.name() + " where 1=1 "); // 加入表名称
      schema = table.schema();
      tablename = table.name();

      // 遍历所有的字段
      Field[] fields = stuClass.getDeclaredFields();// 获取类的字段信息
      for (Field field : fields) {
        if (field.isAnnotationPresent(Column.class)) {
          Column col = field.getAnnotation(Column.class); // 获取列注解
          String columnName = col.name(); // 数据库映射字段
          columns.add(columnName);
          String fieldName = field.getName(); // 获取字段名称
          fieldNames.add(fieldName);
          String methodName = "get" + fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);// 获取字段的get方法

          try {
            // get到field的值
            Method method = stuClass.getMethod(methodName);
            Object fieldValue = method.invoke(annotatedSample);
            // 空字段跳过拼接过程。。。 // 如果没有值，不拼接
            if (fieldValue != null) {
              condition.append(" and " + columnName + "=" + "?");
              params.add(fieldValue);
            }
          } catch (SecurityException e) {
            e.printStackTrace();
          } catch (NoSuchMethodException e) {
            e.printStackTrace();
          } catch (IllegalArgumentException e) {
            e.printStackTrace();
          } catch (IllegalAccessException e) {
            e.printStackTrace();
          } catch (InvocationTargetException e) {
            e.printStackTrace();
          }

        }
      }
    }

    // 附加条件
    for (Restrictions term : restrictions) {
      condition.append(term.getSql());
      params.addAll(term.getParams());
    }

    String sql = "select " + StringUtil.arrayToString(columns) + " from ";
    if (!StringUtil.isEmpty(schema)) {
      sql += schema + ".";
    }

    sql += tablename + condition;
    logger.debug(sql);

    Connection conn = getConnection(); // this.getConnection(dbalias);
    PreparedStatement ps = null;
    ResultSet rs = null;
    T obj = null;
    try {
      // conn.setReadOnly(true);
      ps = conn.prepareStatement(sql);
      this.setPreparedValues(ps, params);
      rs = ps.executeQuery();
      ResultSetMetaData rsmd = rs.getMetaData();
      while (rs.next()) {
        Map<String, Object> map = new HashMap<String, Object>();
        for (int i = 1; i <= rsmd.getColumnCount(); i++) {
          map.put(fieldNames.get(i - 1), rs.getObject(i));
        }
        obj = (T) BeanUtil.autoPackageBean(stuClass, map);
        break;
      }
    } catch (SQLException e) {
      e.printStackTrace();
    } finally {
      this.release(conn, ps, rs);
    }

    return obj;
  }

  /**
   * 根据模板查询 queryBySample
   * 
   */
  public <T> List<T> findByAnnotatedSample(T annotatedSample, Restrictions... restrictions) {
    List<String> columns = new ArrayList<String>();
    List<String> fieldNames = new ArrayList<String>();
    List<Object> params = new ArrayList<Object>();
    StringBuilder condition = new StringBuilder(" where 1=1 ");
    String schema = null;
    String tablename = null;

    // 获取类的class
    Class<? extends Object> stuClass = annotatedSample.getClass();

    /* 通过获取类的类注解，来获取类映射的表名称 */
    if (stuClass.isAnnotationPresent(Table.class)) { // 如果类映射了表
      Table table = (Table) stuClass.getAnnotation(Table.class);
      // sb.append(table.name() + " where 1=1 "); // 加入表名称
      schema = table.schema();
      tablename = table.name();

      /* 遍历所有的字段 */
      Field[] fields = stuClass.getDeclaredFields();// 获取类的字段信息
      for (Field field : fields) {
        if (field.isAnnotationPresent(Column.class)) {
          Column col = field.getAnnotation(Column.class); // 获取列注解
          String columnName = col.name(); // 数据库映射字段
          columns.add(columnName);
          String fieldName = field.getName(); // 获取字段名称
          fieldNames.add(fieldName);
          String methodName = "get" + fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);// 获取字段的get方法

          try {
            // get到field的值
            Method method = stuClass.getMethod(methodName);
            Object fieldValue = method.invoke(annotatedSample);
            /* 空字段跳过拼接过程。。。 */// 如果没有值，不拼接
            if (fieldValue != null) {
              condition.append(" and " + columnName + "=" + "?");
              params.add(fieldValue);
            }
          } catch (SecurityException e) {
            e.printStackTrace();
          } catch (NoSuchMethodException e) {
            e.printStackTrace();
          } catch (IllegalArgumentException e) {
            e.printStackTrace();
          } catch (IllegalAccessException e) {
            e.printStackTrace();
          } catch (InvocationTargetException e) {
            e.printStackTrace();
          }

        }
      }
    }

    // 附加条件
    for (Restrictions term : restrictions) {
      condition.append(term.getSql());
      params.addAll(term.getParams());
    }

    String sql = "select " + StringUtil.arrayToString(columns) + " from ";
    if (!StringUtil.isEmpty(schema)) {
      sql += schema + ".";
    }

    sql += tablename + condition;
    logger.debug(sql.replace("?", "?[{}]"), params.toArray());

    Connection conn = getConnection();
    PreparedStatement ps = null;
    ResultSet rs = null;
    List<T> result = new ArrayList<T>();
    try {
      // conn.setReadOnly(true);
      ps = conn.prepareStatement(sql);
      this.setPreparedValues(ps, params);
      rs = ps.executeQuery();
      ResultSetMetaData rsmd = rs.getMetaData();
      while (rs.next()) {
        Map<String, Object> map = new HashMap<String, Object>();
        for (int i = 1; i <= rsmd.getColumnCount(); i++) {
          map.put(fieldNames.get(i - 1), rs.getObject(i));
        }
        T obj = (T) BeanUtil.autoPackageBean(stuClass, map);
        result.add(obj);
      }
    } catch (SQLException e) {
      e.printStackTrace();
    } finally {
      this.release(conn, ps, rs);
    }

    return result;
  }

  /**
   * 查询一条记录
   * 
   * @param sql
   *          String
   * @param paramList
   *          ArrayList
   * @return HashMap
   */
  public HashMap queryOneAsMap(String sql, Object... params) {
    Connection conn = getConnection();
    PreparedStatement ps = null;
    ResultSet rs = null;
    HashMap hashRow = null;
    try {
      // conn.setReadOnly(true);
      ps = conn.prepareStatement(sql);
      this.setPreparedValues(ps, params);
      rs = ps.executeQuery();
      ResultSetMetaData dbRsMd = rs.getMetaData();
      String[] colNames = new String[dbRsMd.getColumnCount()];
      for (int i = 1; i <= dbRsMd.getColumnCount(); i++) {
        colNames[i - 1] = dbRsMd.getColumnLabel(i); // 获取SQL 字段 别名 对比
        // getColumnName
      }
      while (rs.next()) {
        hashRow = new HashMap();
        for (int i = 0; i < colNames.length; i++) {
          hashRow.put(colNames[i], rs.getObject(colNames[i]));
        }
      }
      return hashRow;
    } catch (SQLException e) {
      logger.info(e.getMessage());
      return null;
    } finally {
      this.release(conn, ps, rs);
    }
  }

  /************************************************************ "select" end ************************************************************/

  /**
   * 查询到处数据为Excel
   * 
   * @param conn
   * @param filePath
   */
  public Workbook exportExcel(String sql, Object... params) {
    return this.exportExcel(null, "0", sql, params);
  }

  public Workbook exportExcel(String sheetTitle, String sql, Object... params) {
    return this.exportExcel(null, sheetTitle, sql, params);
  }

  public Workbook exportExcel(Workbook workbook, String sheetTitle, String sql, Object... params) {
    Connection conn = getConnection();
    PreparedStatement ps = null;
    ResultSet rs = null;

    // 创建一个Excel 2007文件
    if (workbook == null) {
      workbook = new SXSSFWorkbook();
    }
    // 创建一个Excel的Sheet
    Sheet sheet = workbook.createSheet(sheetTitle);

    // 样式 保留两位小数格式
    CellStyle cellStyle = workbook.createCellStyle();
    cellStyle.setDataFormat(HSSFDataFormat.getBuiltinFormat("0.00"));

    // 日期样式
    DataFormat format = workbook.createDataFormat();

    CellStyle dateStyle = workbook.createCellStyle();
    dateStyle.setDataFormat(format.getFormat(DateStyle.SHORTDATEFORMAT.getValue()));

    CellStyle timeStampStyle = workbook.createCellStyle();
    timeStampStyle.setDataFormat(format.getFormat(DateStyle.DATETIMEFORMAT.getValue()));

    CellStyle timeStyle = workbook.createCellStyle();
    timeStyle.setDataFormat(format.getFormat(DateStyle.TIMEFORMAT.getValue()));

    try {
      // conn.setReadOnly(true);
      ps = conn.prepareStatement(sql);
      this.setPreparedValues(ps, params);
      rs = ps.executeQuery();

      ResultSetMetaData rsmd = rs.getMetaData();
      // 数据字段 类型
      List<Integer> types = new ArrayList<Integer>();

      // 添加字段名 为Excel第一行数据 表头
      Row sheetRow = sheet.createRow(0);
      for (int i = 1; i <= rsmd.getColumnCount(); i++) {
        String fieldName = rsmd.getColumnLabel(i); // 获取SQL 字段 别名
        types.add(rsmd.getColumnType(i));
        Cell cell = sheetRow.createCell(i - 1);
        cell.setCellValue(fieldName);
      }

      // 开始 转行 添加数据
      int rowIndex = 1;
      while (rs.next()) {
        sheet.autoSizeColumn(rowIndex + 1, true);
        sheetRow = sheet.createRow(rowIndex++);
        for (int i = 0; i < types.size(); i++) {
          Cell cell = sheetRow.createCell(i);
          if (rs.getObject(i + 1) == null) {
            continue;
          }
          switch (types.get(i)) {
            case Types.DECIMAL:
              cell.setCellValue(rs.getBigDecimal(i + 1).doubleValue());
              break;
            case Types.INTEGER:
              cell.setCellValue(rs.getInt(i + 1));
              break;
            case Types.BIGINT:
              cell.setCellValue(rs.getLong(i + 1));
              break;
            case Types.CHAR:
            case Types.VARCHAR:
            case Types.LONGVARCHAR:
              cell.setCellValue(rs.getString(i + 1));
              break;
            case Types.DATE:
              cell.setCellStyle(dateStyle);
              cell.setCellValue(rs.getDate(i + 1));
              break;
            case Types.TIMESTAMP:
              cell.setCellStyle(timeStampStyle);
              cell.setCellValue(rs.getTimestamp(i + 1));
              break;
            case Types.TIME:
              cell.setCellStyle(timeStyle);
              cell.setCellValue(DateUtil.formatDate(rs.getTime(i + 1), DateStyle.TIMEFORMAT));
              break;
            case Types.DOUBLE:
            case Types.REAL:
              cell.setCellValue(rs.getDouble(i + 1));
              break;
            default:
              logger.error("type : " + types.get(i) + "  -  " + rs.getObject(i + 1));
          }
        }
      }
      rs.close();
      ps.close();
      conn.close();
    } catch (SQLException e) {
      e.printStackTrace();
    } finally {
      this.release(conn, ps, rs);
    }
    return workbook;
  }

  /**
   * 查询到处数据为 CSV
   * 
   * @param conn
   * @param filePath
   * @param encoding
   *          文件内容编码 对应于 DB2 code page
   */
  public void exportCSV(String filePath, String encoding, char split, String sql, Object... params) {
    Connection conn = getConnection();
    PreparedStatement ps = null;
    ResultSet rs = null;
    BufferedWriter bufferedWriter = null;

    try {
      // conn.setReadOnly(true);
      ps = conn.prepareStatement(sql);
      this.setPreparedValues(ps, params);
      rs = ps.executeQuery();

      // 确认流的输出文件和编码格式，此过程创建了“test.txt”实例
      OutputStreamWriter outputWriter = new OutputStreamWriter(new FileOutputStream(filePath), encoding);
      bufferedWriter = new BufferedWriter(outputWriter);

      ResultSetMetaData rsmd = rs.getMetaData();
      // 数据字段类型
      List<Integer> types = new ArrayList<Integer>();

      List<String> titles = new ArrayList<String>(); // 表头
      // 添加字段名 为CSV第一行数据 表头
      for (int i = 1; i <= rsmd.getColumnCount(); i++) {
        String fieldName = rsmd.getColumnLabel(i); // 获取SQL 字段 别名
        types.add(rsmd.getColumnType(i));
        titles.add(fieldName);
      }

      // 开始 添加数据
      while (rs.next()) {
        for (int i = 0; i < types.size(); i++) {
          if (i > 0 && i < types.size()) {
            bufferedWriter.write(",");
          }
          if (rs.getObject(i + 1) != null) {
            switch (types.get(i)) {
              case Types.DECIMAL:
                bufferedWriter.write(rs.getBigDecimal(i + 1).doubleValue() + "");
                break;
              case Types.INTEGER:
                bufferedWriter.write(rs.getInt(i + 1));
                break;
              case Types.BIGINT:
                bufferedWriter.write(rs.getLong(i + 1) + "");
                break;
              case Types.CHAR:
              case Types.VARCHAR:
              case Types.LONGVARCHAR:
                bufferedWriter.write(rs.getString(i + 1));
                break;
              case Types.DATE:
                bufferedWriter.write(rs.getDate(i + 1) + "");
                break;
              case Types.TIMESTAMP:
                bufferedWriter.write(rs.getTimestamp(i + 1) + "");
                break;
              case Types.TIME:
                bufferedWriter.write(rs.getTime(i + 1) + "");
                break;
              case Types.DOUBLE:
              case Types.REAL:
                bufferedWriter.write(rs.getDouble(i + 1) + "");
                break;
              default:
                logger.error("type : " + types.get(i) + "  -  " + rs.getObject(i + 1));
            }
          }
          if (i == types.size() - 1) {
            bufferedWriter.write("\r\n");
          }
        }
      }

      bufferedWriter.close();// 关闭文件流
      rs.close();
      ps.close();
      conn.close();
    } catch (SQLException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    } finally {
      if (bufferedWriter != null) {
        try {
          bufferedWriter.close();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
      this.release(conn, ps, rs);
    }
  }

  /**
   * 查询到处数据为 CSV
   * 
   * @param conn
   * @param filePath
   */
  public void exportCSV_BACK(String filePath, char split, String sql, Object... params) {
    Connection conn = getConnection();
    PreparedStatement ps = null;
    ResultSet rs = null;

    try {
      // conn.setReadOnly(true);
      ps = conn.prepareStatement(sql);
      this.setPreparedValues(ps, params);
      rs = ps.executeQuery();

      // 确认流的输出文件和编码格式，此过程创建了“test.txt”实例
      OutputStreamWriter outputWriter = new OutputStreamWriter(new FileOutputStream(filePath), "GBK");

      // OutputStream out = new FileOutputStream("D:\\demo.txt");//打印到文件。
      // OutputStreamWriter osr = new OutputStreamWriter(out);//输出
      // BufferedWriter bufw = new BufferedWriter(osr);//缓冲

      // CSVWriter writer = new CSVWriter(new FileWriter("yourfile.csv"), ',');
      CSVWriter writer = new CSVWriter(outputWriter, split);

      writer.writeAll(rs, false);

      writer.close();// 关闭文件流
      rs.close();
      ps.close();
      conn.close();
    } catch (SQLException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    } finally {
      this.release(conn, ps, rs);
    }
  }

  /**
   * 读取office 2007 xlsx
   * 
   * @param filePath
   */
  public void loadExcelFile(String schema, String tablename, String filePath) {
    long start = System.currentTimeMillis();
    Connection conn = getConnection();
    PreparedStatement ps = null;
    try {
      // 构造 XSSFWorkbook 对象，strPath 传入文件路径
      Workbook xwb = WorkbookFactory.create(new FileInputStream(filePath));

      // 获取 schema
      if (StringUtil.isEmpty(schema)) {
        // schema = conn.getCatalog();
        // conn.getMetaData().getUserName();
        schema = getCurrentSchema();
        // schema = getSchema(dbalias);
      }

      // 字段类型
      List<Integer> columnTypes = this.getColumnTypes(schema, tablename);

      // 根据表名 生成 Insert语句
      // "insert into CBOD_ECCMRAMR values (?, ?, ?, ?, ?, ?, ?,?, ?, ?, ?, ?, ?, ?,?,?,?)"
      StringBuffer sql;
      if (StringUtil.isEmpty(schema)) {
        sql = new StringBuffer("insert into " + tablename + " values (");
      } else {
        sql = new StringBuffer("insert into " + schema.trim() + "." + tablename + " values (");
      }
      for (int i = 1; i < columnTypes.size(); i++) {
        sql.append("?,");
      }
      sql.append("?)");
      logger.debug(sql.toString());

      ps = conn.prepareStatement(sql.toString());
      conn.setAutoCommit(false);
      int rowCount = 0;

      // 读取第一章表格内容
      Sheet sheet = xwb.getSheetAt(0);
      // 循环输出表格中的内容

      for (int i = sheet.getFirstRowNum(); i <= sheet.getLastRowNum(); i++) {
        Row row = sheet.getRow(i);
        if (row == null) {
          continue;
        }

        List<String> cells = new ArrayList<String>();
        // 判断空行
        int cellnullcount = 0;
        for (int j = row.getFirstCellNum(); j < row.getLastCellNum(); j++) {
          // 通过 row.getCell(j) 获取单元格内容，
          String cellobjTmp = ExcelUtil.convertCellToString(row.getCell(j));
          cells.add(cellobjTmp);
          // 判断空
          if (StringUtil.isEmpty(cellobjTmp)) {
            cellnullcount++;
          }
        }

        // 判断 空行
        if (cellnullcount >= row.getPhysicalNumberOfCells()) {
          continue;
        } else {
          rowCount++;
        }
        // 忽略表头
        if (rowCount > 1) {
          for (int index = 0; index < cells.size(); index++) {
            ps.setObject(index + 1, this.castDBType(columnTypes.get(index), cells.get(index)));
          }

          ps.addBatch();
          if ((rowCount - 1) % BATCHCOUNT == 0) {
            ps.executeBatch();
            conn.commit();
          }
        }
      }

      // 最后插入不足1w条的数据
      ps.executeBatch();
      conn.commit();
      conn.setAutoCommit(true);
      long end = System.currentTimeMillis();
      logger.debug("load into [" + schema + "." + tablename + "] Total : [" + (rowCount - 1) + "] records, Take [" + (float) (end - start) / 1000 + "] seconds . Average : " + 1000
          * (rowCount - 1) / (end - start) + " records/second");
    } catch (InvalidFormatException e) {
      e.printStackTrace();
      System.out.println("请重新保存 Excel ");
    } catch (SQLException e) {
      e.printStackTrace();
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    } finally {
      this.release(conn, ps, null);
    }

  }

  /***
   * 
   * DEL文件入库
   * 
   * @param sql
   * 
   * @param filePath
   *          DEL 文件路径
   * 
   * @param split
   *          字段分隔符
   * 
   */
  public boolean loadDelFile(String schema, String tablename, String filePath, char split) {
    // 数据库文件 分割符号 0X1D : 29
    // split = new String(new byte[] { 29 });
    long start = System.currentTimeMillis();
    Connection connection = this.getConnection();
    PreparedStatement ps = null;
    BufferedReader reader = null;

    boolean flag = false;
    int loadCount = 0; // 批量计数
    try {
      // TODO 不靠谱
      String encode = IOHandler.getCharSetEncoding(filePath);
      if (!encode.equals(CharSetType.GBK.getValue())) {
        logger.warn("IOHandler get File : {}  character set  : {}  incorrect", filePath, encode);
        encode = CharSetType.GBK.getValue(); //
      }

      reader = new BufferedReader(new InputStreamReader(new FileInputStream(filePath), encode));
      // 获取字段类型
      List<Integer> columnTypes = this.getColumnTypes(schema, tablename);
      int cloumnCount = columnTypes.size(); // 表 字段数
      // 根据表名 生成 Insert语句
      // "insert into CBOD_ECCMRAMR values (?, ?, ?, ?, ?, ?, ?,?, ?, ?, ?, ?, ?, ?,?,?,?)"
      StringBuffer sql;
      if (schema == null || "".equals(schema.trim())) {
        sql = new StringBuffer("insert into " + tablename + " values (");
      } else {
        sql = new StringBuffer("insert into " + schema.trim() + "." + tablename + " values (");
      }
      for (int i = 1; i < cloumnCount; i++) {
        sql.append("?,");
      }
      sql.append("?)");

      logger.debug(sql.toString());

      ps = connection.prepareStatement(sql.toString());
      connection.setAutoCommit(false);

      String line;
      while ((line = reader.readLine()) != null) {
        String[] lineData = line.split(String.valueOf(split));
        int dataNum = lineData.length; // 数据文件 字段数
        for (int i = 0; i < cloumnCount; i++) {
          try {
            if (i + 1 > dataNum) {
              ps.setObject(i + 1, null);
            } else {
              ps.setObject(i + 1, this.castDBType(columnTypes.get(i), lineData[i]));
            }
          } catch (Exception e) {
            logger.error(columnTypes.get(i) + " :  " + lineData[i]);
            e.printStackTrace();
          }
        }
        ps.addBatch();
        // 1w条记录插入一次
        if (++loadCount % BATCHCOUNT == 0) {
          ps.executeBatch();
          connection.commit();
        }
      }
      // 最后插入不足1w条的数据
      ps.executeBatch();
      connection.commit();
      connection.setAutoCommit(true);
      flag = true;
      long end = System.currentTimeMillis();
      logger.debug("load into [" + schema + "." + tablename + "] Total : [" + loadCount + "] records, Take [" + (float) (end - start) / 1000 + "] seconds . Average : " + 1000
          * loadCount / (end - start) + " records/second");
    } catch (UnsupportedEncodingException e) {
      e.printStackTrace();
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    } catch (SQLException e) {
      e.printStackTrace();
      e.getNextException().printStackTrace();
      logger.error(e.getMessage());
    } finally {
      try {
        this.release(connection, ps, null);
        if (reader != null) {
          reader.close();
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
    return flag;
  }

  /***
   * CSV（默认以逗号分割的） 文件入库 (char)44
   * 
   * @param tablename
   *          入库表名
   * @param filePath
   *          文件路径
   * @param split
   *          字段分隔符
   */
  public void loadCsvFile(String schema, String tablename, String filepath) {
    this.loadCsvFile(schema, tablename, filepath, (char) 44);
  }

  /***
   * CSV（默认以逗号分割的） 文件入库
   * 
   * @param tablename
   *          入库表名
   * @param filePath
   *          文件路径
   * @param split
   *          字段分隔符
   */
  public void loadCsvFile(String schema, String tablename, String filepath, char split) {
    long start = System.currentTimeMillis();
    Connection connection = getConnection();

    PreparedStatement ps = null;
    CSVReader reader = null;
    int loadCount = 0; // 批量计数

    try {
      String encode = IOHandler.getCharSetEncoding(filepath);
      reader = new CSVReader(new BufferedReader(new InputStreamReader(new FileInputStream(filepath), encode)), split);
      // 获取字段类型
      List<Integer> columnTypes = this.getColumnTypes(schema, tablename);
      int cloumnCount = columnTypes.size();
      // 根据表名 生成 Insert语句
      // "insert into CBOD_ECCMRAMR values (?, ?, ?, ?, ?, ?, ?,?, ?, ?, ?, ?, ?, ?,?,?,?)"
      StringBuffer sql;
      if (schema == null || "".equals(schema.trim())) {
        sql = new StringBuffer("insert into " + tablename + " values (");
      } else {
        sql = new StringBuffer("insert into " + schema.trim() + "." + tablename + " values (");
      }
      for (int i = 1; i < cloumnCount; i++) {
        sql.append("?,");
      }
      sql.append("?)");
      logger.debug(sql.toString());

      ps = connection.prepareStatement(sql.toString());
      connection.setAutoCommit(false);
      String[] csvRow = null; // row
      while ((csvRow = reader.readNext()) != null) {
        // for (int i = 0; i < (csvRow.length > cloumnCount ? cloumnCount :
        // csvRow.length); i++) {
        // try {
        // ps.setObject(i + 1, this.castDBType(columnTypes.get(i), csvRow[i]));
        // } catch (Exception e) {
        // System.out.println(columnTypes.get(i) + " :  " + csvRow[i]);
        // e.printStackTrace();
        // }
        // }
        int dataNum = csvRow.length; // 数据文件 字段数
        for (int i = 0; i < cloumnCount; i++) {
          try {
            if (i + 1 > dataNum) {
              ps.setObject(i + 1, null);
            } else {
              ps.setObject(i + 1, this.castDBType(columnTypes.get(i), csvRow[i]));
            }
          } catch (Exception e) {
            System.out.println(columnTypes.get(i) + " :  " + csvRow[i]);
            e.printStackTrace();
          }
        }
        ps.addBatch();
        // 1w条记录插入一次
        if (++loadCount % BATCHCOUNT == 0) {
          ps.executeBatch();
          connection.commit();
        }
      }
      // 最后插入不足1w条的数据
      ps.executeBatch();
      connection.commit();
      connection.setAutoCommit(true);
      long end = System.currentTimeMillis();
      logger.debug("load into [" + schema + "." + tablename + "] Total : [" + loadCount + "] records, Take [" + (float) (end - start) / 1000 + "] seconds . Average : " + 1000
          * loadCount / (end - start) + " records/second");
    } catch (UnsupportedEncodingException e) {
      e.printStackTrace();
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    } catch (SQLException e) {
      e.printStackTrace();
      logger.error(e.getMessage());
    } finally {
      try {
        this.release(connection, ps, null);
        if (reader != null) {
          reader.close();
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

}