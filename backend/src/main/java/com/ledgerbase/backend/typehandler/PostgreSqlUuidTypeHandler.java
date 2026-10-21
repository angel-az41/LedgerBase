package com.ledgerbase.backend.typehandler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

// ============================================================
// LEDGERBASE — POSTGRESQL UUID TYPE HANDLER
// ============================================================

/**
 * Converts Java UUID values to and from PostgreSQL UUID.
 *
 * WHY THIS IS NECESSARY:
 *
 * MyBatis requires a registered TypeHandler when
 * mapping UUID columns to Java UUID objects.
 *
 * PostgreSQL JDBC natively supports java.util.UUID.
 *
 * This handler uses that native support rather than
 * converting UUID values into SQL text.
 *
 * SUPPORTED OPERATIONS:
 *
 * - Writing UUID parameters to prepared statements.
 * - Reading UUID values from result sets.
 * - Reading UUID values from callable statements.
 *
 * This handler supports authentication sessions
 * without changing the PostgreSQL schema.
 */
@MappedTypes(UUID.class)
public class PostgreSqlUuidTypeHandler
    extends BaseTypeHandler<UUID> {

    // ========================================================
    // WRITE UUID PARAMETER
    // ========================================================

    /**
     * Bind a Java UUID to a SQL statement.
     *
     * PostgreSQL JDBC recognizes the UUID object
     * and maps it to the native PostgreSQL UUID type.
     */
    @Override
    public void setNonNullParameter(
        PreparedStatement statement,
        int index,
        UUID parameter,
        JdbcType jdbcType
    ) throws SQLException {

        statement.setObject(index, parameter);
    }


    // ========================================================
    // READ UUID BY COLUMN NAME
    // ========================================================

    /**
     * Read a UUID using its column name.
     *
     * Example:
     *
     * SELECT id FROM auth_sessions
     */
    @Override
    public UUID getNullableResult(
        ResultSet resultSet,
        String columnName
    ) throws SQLException {

        return resultSet.getObject(
            columnName,
            UUID.class
        );
    }


    // ========================================================
    // READ UUID BY COLUMN INDEX
    // ========================================================

    /**
     * Read a UUID using its result-set position.
     */
    @Override
    public UUID getNullableResult(
        ResultSet resultSet,
        int columnIndex
    ) throws SQLException {

        return resultSet.getObject(
            columnIndex,
            UUID.class
        );
    }


    // ========================================================
    // READ UUID FROM CALLABLE STATEMENT
    // ========================================================

    /**
     * Read a UUID returned by a database function
     * or stored procedure.
     */
    @Override
    public UUID getNullableResult(
        CallableStatement statement,
        int columnIndex
    ) throws SQLException {

        return statement.getObject(
            columnIndex,
            UUID.class
        );
    }
}
