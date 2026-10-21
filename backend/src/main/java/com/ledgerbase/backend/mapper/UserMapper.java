package com.ledgerbase.backend.mapper;

import com.ledgerbase.backend.model.User;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

// ============================================================
// LEDGERBASE — USER MYBATIS MAPPER
// ============================================================

/**
 * Database access operations for LedgerBase users.
 *
 * DATABASE TABLE:
 *
 * users
 *
 * RESPONSIBILITIES:
 *
 * 1. Retrieve a user by email for authentication.
 * 2. Retrieve a user by ID for identity validation.
 *
 * MyBatis executes the SQL and converts database rows
 * into User model instances.
 *
 * SECURITY:
 *
 * - This mapper is used only by trusted backend services.
 * - password_hash is never returned directly to clients.
 * - Login password verification belongs in the service
 *   layer, not in SQL or in this mapper.
 */
@Mapper
public interface UserMapper {

    // ========================================================
    // 1. FIND USER BY EMAIL
    // ========================================================

    /**
     * Retrieve a user using their stored email address.
     *
     * Used during the login workflow.
     *
     * The database already has a UNIQUE constraint
     * on the email column.
     *
     * This initial query uses exact matching.
     * We will define consistent email normalization
     * rules before implementing registration.
     *
     * @param email stored email address to find
     * @return matching user, or null if not found
     */
    @Select("""
        SELECT
            id,
            email,
            password_hash,
            full_name,
            created_at
        FROM users
        WHERE email = #{email}
        LIMIT 1
        """)
    @Results(
        id = "userResult",
        value = {
            @Result(
                column = "id",
                property = "id",
                id = true
            ),
            @Result(
                column = "email",
                property = "email"
            ),
            @Result(
                column = "password_hash",
                property = "passwordHash"
            ),
            @Result(
                column = "full_name",
                property = "fullName"
            ),
            @Result(
                column = "created_at",
                property = "createdAt"
            )
        }
    )
    User findByEmail(
        @Param("email") String email
    );


    // ========================================================
    // 2. FIND USER BY ID
    // ========================================================

    /**
     * Retrieve an existing user using their database ID.
     *
     * This method will support authenticated identity
     * lookup and future JWT validation workflows.
     *
     * Reuses the mapping defined in findByEmail().
     *
     * @param userId database user identifier
     * @return matching user, or null if not found
     */
    @Select("""
        SELECT
            id,
            email,
            password_hash,
            full_name,
            created_at
        FROM users
        WHERE id = #{userId}
        """)
    @ResultMap("userResult")
    User findById(
        @Param("userId") Long userId
    );
}
