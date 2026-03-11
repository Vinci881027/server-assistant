package com.linux.ai.serverassistant.service.command;

import com.linux.ai.serverassistant.service.security.AdminAuthorizationService;
import com.linux.ai.serverassistant.service.user.UserManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserManagementRouterTest {

    private UserManagementService userManagementService;
    private UserManagementRouter router;

    @BeforeEach
    void setUp() {
        userManagementService = mock(UserManagementService.class);
        AdminAuthorizationService adminAuthorizationService = mock(AdminAuthorizationService.class);
        router = new UserManagementRouter(userManagementService, adminAuthorizationService);

        when(adminAuthorizationService.isAdmin("alice")).thenReturn(true);
        when(adminAuthorizationService.isAdmin("bob")).thenReturn(false);
    }

    @Test
    void tryRoute_nonAdminIntent_shouldReturnPermissionDenied() {
        UserManagementRouter.RouteResult result = router.tryRoute("增加使用者", "conv-1", "bob");

        assertTrue(result.matched());
        assertTrue(result.response().contains("僅限管理員"));
    }

    @Test
    void tryRoute_createUserFlow_shouldAskUsernameThenPassword() {
        UserManagementRouter.RouteResult start = router.tryRoute("增加使用者", "conv-1", "alice");
        assertTrue(start.matched());
        assertTrue(start.response().contains("username"));

        UserManagementRouter.RouteResult usernameStep = router.tryRoute("devops_user", "conv-1", "alice");
        assertTrue(usernameStep.matched());
        assertTrue(usernameStep.response().contains("password"));

        verify(userManagementService, never()).manageUsers("add", "devops_user", null, false, "alice");
    }

    @Test
    void tryRoute_createUserIntent_withNaturalLongerGap_shouldMatch() {
        UserManagementRouter.RouteResult result = router.tryRoute("我想新增一個全新的使用者帳號", "conv-1", "alice");

        assertTrue(result.matched());
        assertTrue(result.response().contains("username"));
    }

    @Test
    void tryRoute_createUserIntent_withTooLongGap_shouldNotMatch() {
        UserManagementRouter.RouteResult result = router.tryRoute("我想新增一個真的非常非常新的使用者帳號", "conv-1", "alice");

        assertFalse(result.matched());
    }

    @Test
    void tryRoute_passwordContainsLineFeed_shouldReject() {
        moveToPasswordStep();

        UserManagementRouter.RouteResult passwordStep = router.tryRoute("abc\ndef", "conv-1", "alice");
        assertTrue(passwordStep.matched());
        assertTrue(passwordStep.response().contains("不可包含換行、CR 或 NUL"));

        verifyNoUserCreationAttempt();
    }

    @Test
    void tryRoute_passwordContainsCarriageReturn_shouldReject() {
        moveToPasswordStep();

        UserManagementRouter.RouteResult passwordStep = router.tryRoute("abc\rdef", "conv-1", "alice");
        assertTrue(passwordStep.matched());
        assertTrue(passwordStep.response().contains("不可包含換行、CR 或 NUL"));

        verifyNoUserCreationAttempt();
    }

    @Test
    void tryRoute_passwordContainsNullByte_shouldReject() {
        moveToPasswordStep();

        UserManagementRouter.RouteResult passwordStep = router.tryRoute("abc\u0000def", "conv-1", "alice");
        assertTrue(passwordStep.matched());
        assertTrue(passwordStep.response().contains("不可包含換行、CR 或 NUL"));

        verifyNoUserCreationAttempt();
    }

    @Test
    void tryRoute_passwordExceedsMaxLength_shouldReject() {
        moveToPasswordStep();
        String tooLongPassword = "a".repeat(129);

        UserManagementRouter.RouteResult passwordStep = router.tryRoute(tooLongPassword, "conv-1", "alice");
        assertTrue(passwordStep.matched());
        assertTrue(passwordStep.response().contains("長度不可超過 128"));

        verifyNoUserCreationAttempt();
    }

    @Test
    void tryRoute_passwordAtMaxLength_shouldProceedToCreate() {
        moveToPasswordStep();
        String maxLengthPassword = "a".repeat(128);
        when(userManagementService.manageUsers(eq("add"), eq("devops_user"), any(), eq(false), eq("alice")))
            .thenReturn("[CMD:::useradd devops_user:::]");

        UserManagementRouter.RouteResult passwordStep = router.tryRoute(maxLengthPassword, "conv-1", "alice");
        assertTrue(passwordStep.matched());
        assertTrue(passwordStep.response().contains("請先點擊確認完成建立帳號"));

        verify(userManagementService).manageUsers(
            eq("add"),
            eq("devops_user"),
            argThat(chars -> chars != null && chars.length == 128),
            eq(false),
            eq("alice")
        );
    }

    private void moveToPasswordStep() {
        UserManagementRouter.RouteResult start = router.tryRoute("增加使用者", "conv-1", "alice");
        assertTrue(start.matched());

        UserManagementRouter.RouteResult usernameStep = router.tryRoute("devops_user", "conv-1", "alice");
        assertTrue(usernameStep.matched());
    }

    private void verifyNoUserCreationAttempt() {
        verify(userManagementService, never()).manageUsers(eq("add"), anyString(), any(), anyBoolean(), anyString());
    }
}
