package com.avies.backend.exam;

import org.springframework.jdbc.datasource.AbstractDataSource;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.sql.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Đếm lệnh JDBC thật trên PostgreSQL test để chặn hồi quy số lần trao đổi DB; không thay kết quả SQL. */
final class OralJdbcCounter extends AbstractDataSource {
    /** Datasource container do test sở hữu, không đọc cấu hình live. */ private final DataSource source;
    /** Chỉ đếm execute, không đếm getter hoặc thao tác transaction ở JDBC. */ final AtomicInteger executions=new AtomicInteger();
    /** Không mở connection khi tạo bộ đếm. */ OralJdbcCounter(DataSource source) { this.source=source; }
    @Override public Connection getConnection() throws SQLException { return connection(source.getConnection()); }
    @Override public Connection getConnection(String user,String password) throws SQLException { return connection(source.getConnection(user,password)); }
    /** Bọc statement, vẫn dùng connection và transaction thật. */
    private Connection connection(Connection delegate) {
        return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args) -> {
            Object result=invoke(delegate,method,args);
            if (result instanceof Statement statement && (method.getName().equals("prepareStatement") || method.getName().equals("createStatement"))) {
                Class<?> contract=statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{contract},(p,m,a) -> {
                    if (m.getName().startsWith("execute")) executions.incrementAndGet();
                    return invoke(statement,m,a);
                });
            }
            return result;
        });
    }
    /** Giữ SQLException gốc để test vẫn kiểm tra NOWAIT/rollback như production. */
    private static Object invoke(Object target,Method method,Object[] args) throws Throwable {
        try { return method.invoke(target,args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
