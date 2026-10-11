package com.ledgerbase.backend.service;

import com.ledgerbase.backend.mapper.AccountMapper;
import com.ledgerbase.backend.model.Account;
import com.ledgerbase.backend.mapper.BusinessMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AccountService.
 *
 * Mockito replaces the real database mapper with a fake,
 * allowing us to test the business logic independently
 * of PostgreSQL.
 */
class AccountServiceTest {

    private AccountMapper accountMapper;
    private AccountService accountService;
    private BusinessMapper businessMapper;

    /**
     * Before each test, create a fresh fake mapper
     * and AccountService instance.
     */
    @BeforeEach
    void setUp() {
        accountMapper = mock(AccountMapper.class);
        businessMapper = mock(BusinessMapper.class);

        accountService = new AccountService(
            accountMapper,
            businessMapper
        );

        // Simulate a business that exists for ordinary tests.
        when(businessMapper.existsById(1L)).thenReturn(true);
    }

    /**
     * A business should be able to retrieve its accounts.
     */
    @Test
    void getAccountsReturnsBusinessAccounts() {
        Account cash = new Account();
        cash.setCode("1000");
        cash.setName("Cash");
        cash.setAccountType("ASSET");

        when(accountMapper.findByBusinessId(1L))
            .thenReturn(java.util.List.of(cash));

        var result = accountService.getAccounts(1L);

        assertEquals(1, result.size());
        assertEquals("Cash", result.get(0).getName());

        verify(accountMapper).findByBusinessId(1L);
    }

    /**
     * Creating an account with a blank code must fail.
     * The mapper must not insert invalid data.
     */
    @Test
    void createAccountRejectsBlankCode() {
        assertThrows(
            IllegalArgumentException.class,
            () -> accountService.createAccount(
                1L, " ", "Cash", "ASSET"
            )
        );

        verify(accountMapper, never()).insert(any());
    }

    /**
     * Creating an account with a blank name must fail.
     */
    @Test
    void createAccountRejectsBlankName() {
        assertThrows(
            IllegalArgumentException.class,
            () -> accountService.createAccount(
                1L, "1000", " ", "ASSET"
            )
        );

        verify(accountMapper, never()).insert(any());
    }

    /**
     * Only the five supported account types are valid.
     */
    @Test
    void createAccountRejectsInvalidType() {
        assertThrows(
            IllegalArgumentException.class,
            () -> accountService.createAccount(
                1L, "1000", "Cash", "INVALID"
            )
        );

        verify(accountMapper, never()).insert(any());
    }

    /**
     * Valid account creation calls the mapper and
     * returns the newly created account.
     *
     * Simulate PostgreSQL generating the account ID.
     */
    @Test
    void createAccountSucceeds() {
        Account savedAccount = new Account();
        savedAccount.setId(10L);
        savedAccount.setBusinessId(1L);
        savedAccount.setCode("1000");
        savedAccount.setName("Cash");
        savedAccount.setAccountType("ASSET");

        // MyBatis normally writes the generated ID
        // back into the Account object after INSERT.
        doAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            account.setId(10L);
            return 1;
        }).when(accountMapper).insert(any(Account.class));

        when(accountMapper.findById(1L, 10L))
            .thenReturn(savedAccount);

        Account result = accountService.createAccount(
            1L, "1000", "Cash", "ASSET"
        );

        assertEquals(10L, result.getId());
        assertEquals("Cash", result.getName());

        verify(accountMapper).insert(any(Account.class));
        verify(accountMapper).findById(1L, 10L);
    }

    /**
     * Renaming an account should call the mapper.
     */
    @Test
    void renameAccountSucceeds() {
        when(accountMapper.updateName(1L, 10L, "Main Cash"))
            .thenReturn(1);

        boolean result = accountService.renameAccount(
            1L, 10L, "Main Cash"
        );

        assertTrue(result);

        verify(accountMapper)
            .updateName(1L, 10L, "Main Cash");
    }

    /**
     * Deactivating an account should call the mapper.
     */
    @Test
    void deactivateAccountSucceeds() {
        when(accountMapper.deactivate(1L, 10L))
            .thenReturn(1);

        boolean result = accountService.deactivateAccount(
            1L, 10L
        );

        assertTrue(result);

        verify(accountMapper).deactivate(1L, 10L);
    }
}
