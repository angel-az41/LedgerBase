package com.ledgerbase.backend.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * MyBatis mapper for checking PostgreSQL connectivity.
 *
 * MyBatis executes the SQL and maps the result
 * to a Java return value.
 */
@Mapper
public interface DatabaseMapper {

    // Query PostgreSQL to get the current database name.
    @Select("SELECT current_database()")
    String getDatabaseName();
}
