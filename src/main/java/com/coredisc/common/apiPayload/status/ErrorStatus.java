package com.coredisc.common.apiPayload.status;


import com.coredisc.common.apiPayload.code.BaseErrorCode;
import com.coredisc.common.apiPayload.code.ErrorReasonDTO;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ErrorStatus implements BaseErrorCode {

    // 가장 일반적인 응답
    _INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON500", "서버 에러, 관리자에게 문의 바랍니다."),
    _BAD_REQUEST(HttpStatus.BAD_REQUEST,"COMMON400","잘못된 요청입니다."),
    _UNAUTHORIZED(HttpStatus.UNAUTHORIZED,"COMMON401","인증이 필요합니다."),
    _FORBIDDEN(HttpStatus.FORBIDDEN, "COMMON403", "금지된 요청입니다."),

    // 인증 관련 에러
    PASSWORD_NOT_EQUAL(HttpStatus.BAD_REQUEST, "AUTH4001", "비밀번호가 일치하지 않습니다."),
    EMAIL_CODE_EXPIRED(HttpStatus.BAD_REQUEST, "AUTH4002", "인증 코드가 만료되었습니다. 다시 요청해주세요."),
    CODE_NOT_EQUAL(HttpStatus.BAD_REQUEST, "AUTH4003", "인증 코드가 올바르지 않습니다."),
    USERNAME_ALREADY_EXISTS(HttpStatus.CONFLICT, "AUTH4004", "이미 사용 중인 계정명입니다."),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "AUTH4005", "이미 사용 중인 이메일입니다."),
    NICKNAME_ALREADY_EXISTS(HttpStatus.CONFLICT, "AUTH4006", "이미 사용 중인 닉네임입니다."),
    DUPLICATED_RESOURCE(HttpStatus.CONFLICT, "AUTH4007", "이미 사용 중인 값이 있습니다."),
    REQUIRED_TERMS_NOT_AGREED(HttpStatus.BAD_REQUEST, "AUTH4008", "필수 동의 약관 항목에 동의하지 않았습니다."),
    INVALID_TERMS_INCLUDED(HttpStatus.BAD_REQUEST, "AUTH4009", "유효하지 않은 이용 약관 향목에 동의하였습니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH4010", "유효하지 않은 토큰입니다."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "AUTH4011", "토큰이 만료되었습니다."),
    TOKEN_LOGGED_OUT(HttpStatus.UNAUTHORIZED, "AUTH4012", "이 토큰은 로그아웃되어 더 이상 유효하지 않습니다."),
    SAME_EMAIL_REQUEST(HttpStatus.CONFLICT, "AUTH4013", "현재 사용하고 계신 이메일과 동일합니다."),
    SAME_USERNAME_REQUEST(HttpStatus.CONFLICT, "AUTH4014", "현재 사용하고 계신 아이디와 동일합니다."),
    INVALID_CURRENT_PASSWORD(HttpStatus.BAD_REQUEST, "AUTH4015", "현재 사용하고 계신 비밀번호와 일치하지 않습니다."),
    PASSWORD_CHECK_NOT_EQUAL(HttpStatus.BAD_REQUEST, "AUTH4016", "재확인 비밀번호가 일치하지 않습니다."),
    UNSUPPORTED_PROVIDER(HttpStatus.BAD_REQUEST, "AUTH4017", "지원하지 않는 provider입니다."),

    // 인증코드 메일 전송 관련 에러
    UNSUPPORTED_EMAIL_REQUEST_TYPE(HttpStatus.BAD_REQUEST, "EMAIL4001", "지원하지 않는 EmailRequestType 입니다."),
    EMAIL_SEND_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "EMAIL5001", "메일 전송에 실패했습니다."),
    EMAIL_WRITE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "EMAIL5002", "메일 작성에 실패했습니다."),

    // 게시글 관련 에러
    POST_NOT_FOUND(HttpStatus.NOT_FOUND, "POST4001", "게시글을 찾을 수 없습니다."),
    POST_ALREADY_EXISTS(HttpStatus.CONFLICT, "POST4002", "이미 해당 날짜에 게시글이 존재합니다."),
    POST_ALREADY_PUBLISHED(HttpStatus.CONFLICT, "POST4003", "이미 발행된 게시글입니다."),
    POST_NOT_READY_TO_PUBLISH(HttpStatus.BAD_REQUEST, "POST4004", "발행할 수 없는 상태입니다."),
    NOT_POST_OWNER(HttpStatus.FORBIDDEN, "POST4005", "게시글 작성자가 아닙니다."),
    INCOMPLETE_TODAY_QUESTIONS(HttpStatus.BAD_REQUEST, "POST4006", "오늘의 질문이 4개가 설정되지 않았습니다."),
    INVALID_QUESTION_ORDER(HttpStatus.BAD_REQUEST, "POST4007", "잘못된 질문 순서입니다."),
    INVALID_DATE(HttpStatus.BAD_REQUEST, "POST4008", "잘못된 날짜입니다."),

    // 이미지 파일 관련 에러
    FILE_STORE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "FILE5001", "파일 저장에 실패했습니다."),
    FILE_NOT_FOUND(HttpStatus.NOT_FOUND, "FILE404", "파일을 찾을 수 없습니다."),
    THUMBNAIL_CREATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "FILE5002", "썸네일 생성에 실패했습니다."),
    FILE_UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "FILE5003", "파일 업로드에 실패했습니다."),
    EMPTY_FILE(HttpStatus.BAD_REQUEST, "FILE4001", "빈 파일입니다."),
    FILE_SIZE_EXCEEDED(HttpStatus.BAD_REQUEST, "FILE4002", "파일 크기가 너무 큽니다. (최대 10MB)"),
    INVALID_FILE_NAME(HttpStatus.BAD_REQUEST, "FILE4003", "잘못된 파일명입니다."),
    INVALID_FILE_TYPE(HttpStatus.BAD_REQUEST, "FILE4004", "지원하지 않는 파일 형식입니다. (jpg, jpeg, png, gif, webp만 허용)"),
    UNKNOWN_ERROR(HttpStatus.BAD_REQUEST, "FILE4005", "예외가 발생했습니다.(DB 에러, NullPointer 등)"),

    //통계 관련 에러
    STATS_NOT_FOUND(HttpStatus.NOT_FOUND, "STATS4001","요청한 통계 데이터를 찾을 수 없습니다."),
    STATS_NOT_MEANINGFUL(HttpStatus.NO_CONTENT, "STATS4002","통계 데이터가 유의미하지 않거나 충분하지 않습니다."),
    INVALID_SELECTED_OPTION(HttpStatus.BAD_REQUEST, "STATS4003", "선택형 일기 옵션 번호가 잘못되었습니다."),
    INVALID_DAILY_TYPE(HttpStatus.BAD_REQUEST, "STATS4004", "존재하지 않는 dailyType입니다."),
    INVALID_LABEL_MAPPING(HttpStatus.BAD_REQUEST, "STATS4005", "라벨 매핑에 실패했습니다."),


    // 답변 관련 예외
    ANSWER_NOT_FOUND(HttpStatus.NOT_FOUND, "ANSWER4004", "답변을 찾을 수 없습니다."),

    // 멤버 관련 에러
    MEMBER_NOT_FOUND(HttpStatus.BAD_REQUEST, "MEMBER4001", "사용자가 없습니다."),


    // 이용 약관 관련 에러
    TERMS_NOT_FOUND(HttpStatus.NOT_FOUND, "TERMS4001", "존재하지 않는 이용 약관 항목입니다."),

    // 프로필 이미지 관련 에러
    DEFAULT_PROFILE_IMG_NOT_FOUND(HttpStatus.NOT_FOUND, "PROFILE_IMG4001", "기본 프로필 이미지가 존재하지 않습니다."),
    PROFILE_IMG_ALREADY_DEFAULT(HttpStatus.CONFLICT,"PROFILE_IMG4002", "이미 기본 프로필 이미지로 설정되어 있습니다."),

    // 마이홈 관련 에러
    SELF_PROFILE_REQUEST(HttpStatus.BAD_REQUEST, "MY_HOME4001", "자기 자신의 프로필은 해당 API로 요청할 수 없습니다."),

    // 카테고리 관련 에러
    CATEGORY_NOT_FOUND(HttpStatus.BAD_REQUEST, "CATEGORY4001", "헤딩 카테고리가 없습니다."),

    // 질문 관련 에러
    BASIC_QUESTION_NOT_FOUND(HttpStatus.BAD_REQUEST, "QUESTION4001", "해당되는 기본 질문이 없습니다."),
    OFFICIAL_QUESTION_NOT_FOUND(HttpStatus.BAD_REQUEST, "QUESTION4002", "해당되는 공유 질문이 없습니다."),
    PERSONAL_QUESTION_NOT_FOUND(HttpStatus.BAD_REQUEST, "QUESTION4003", "해당되는 저장 질문이 없습니다."),
    QUESTION_TYPE_NOT_FOUND(HttpStatus.BAD_REQUEST, "QUESTION4004", "해당되는 저장 타입이 없습니다."),
    DUPLICATE_FIXED_TODAY_QUESTION_ORDER(HttpStatus.CONFLICT, "QUESTION4005", "해당 순서의 질문이 이번 달에 이미 존재합니다."),
    DUPLICATE_RANDOM_TODAY_QUESTION_ORDER(HttpStatus.CONFLICT, "QUESTION4006", "이미 오늘 설정한 랜덤 질문이 존재합니다."),
    UNAUTHORIZED_PERSONAL_QUESTION_ACCESS(HttpStatus.FORBIDDEN, "QUESTION4007", "해당 질문에 대한 수정/삭제 권한이 없습니다."),
    PERSONAL_QUESTION_USED_IN_TODAY_QUESTION(HttpStatus.CONFLICT, "QUESTION4008", "고정 또는 랜덤 질문으로 사용된 질문은 삭제할 수 없습니다."),
    CANNOT_SELECT_OWN_OFFICIAL_QUESTION(HttpStatus.FORBIDDEN, "QUESTION4009", "자신이 작성한 공유 질문은 저장할 수 없습니다."),
    MEMBER_OFFICIAL_QUESTION_NOT_FOUND(HttpStatus.NOT_FOUND, "QUESTION4010", "저장한 공유 질문이 존재하지 않습니다."),
    ALREADY_SAVED_OFFICIAL_QUESTION(HttpStatus.BAD_REQUEST, "QUESTION4011", "이미 해당 공유 질문을 저장했습니다."),
    INVALID_OFFICIAL_QUESTION_FILTER_COMBINATION(HttpStatus.BAD_REQUEST, "QUESTION4012", "카테고리와 즐겨찾기 필터는 동시에 사용할 수 없습니다. (favorite=true면 categoryId는 넘기지 말아야 함, categoryId 넘길 시 favorite는 안넘기거나 false여야 함)"),
    ALREADY_FAVORITE_OFFICIAL_QUESTION(HttpStatus.BAD_REQUEST, "QUESTION4013", "이미 즐겨찾기된 질문입니다."),
    ALREADY_NOT_FAVORITE_OFFICIAL_QUESTION(HttpStatus.BAD_REQUEST, "QUESTION4014", "즐겨찾기가 되어있지 않은 질문입니다."),
    CANNOT_FAVORITE_OTHERS_QUESTION(HttpStatus.FORBIDDEN, "QUESTION4015", "자신이 작성한 질문만 즐겨찾기 할 수 있습니다."),
    ALREADY_SAVED_PERSONAL_QUESTION(HttpStatus.BAD_REQUEST, "QUESTION4016", "이미 해당 개인 질문을 저장했습니다."),

    // Follow 관련 에러
    SELF_FOLLOW_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "FOLLOW4001", "자기 자신은 팔로우할 수 없습니다."),
    ALREADY_FOLLOWING(HttpStatus.BAD_REQUEST, "FOLLOW4002", "이미 팔로우한 이력이 있습니다."),
    FOLLOW_NOT_FOUND(HttpStatus.NOT_FOUND, "FOLLOW4003", "팔로우한 이력이 없습니다."),
    SELF_UNFOLLOW_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "FOLLOW4004", "자기 자신은 언팔로우할 수 없습니다."),

    // 차단 관련 에러
    BLOCKED_MEMBER_REQUEST(HttpStatus.BAD_REQUEST, "Block4001", "차단된 사용자입니다."),
    SELF_BLOCK_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "BLOCK4001", "자기 자신은 차단할 수 없습니다."),
    ALREADY_BLOCKING(HttpStatus.BAD_REQUEST, "BLOCK4002", "이미 차단한 이력이 있습니다."),
    BLOCK_NOT_FOUND(HttpStatus.NOT_FOUND, "BLOCK4003", "차단한 이력이 없습니다."),
    SELF_UNBLOCK_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "BLOCK4004", "자기 자신은 차단 취소 할 수 없습니다."),
    BLOCK_RELATIONSHIP_EXISTS(HttpStatus.BAD_REQUEST, "BLOCK4005", "차단 관계가 존재합니다."),

    // Circle 관련 에러
    SELF_CIRCLE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "CIRCLE4001", "자기 자신은 친한 친구로 설정할 수 없습니다."),
    MUST_BE_MUTUAL_FOLLOW_TO_BE_CIRCLE(HttpStatus.BAD_REQUEST, "CIRCLE4002", "서로 맞팔로우 관계여야 친한친구로 등록할 수 있습니다."),

    // Disc 관련 에러
    DISC_NOT_FOUND(HttpStatus.NOT_FOUND, "DISC4001", "디스크가 존재하지 않습니다."),

    // 검색 관련 에러
    INVALID_SEARCH_KEYWORD(HttpStatus.BAD_REQUEST, "SEARCH4001", "검색어는 비어 있을 수 없습니다."),
    SEARCH_HISTORY_NOT_FOUND(HttpStatus.NOT_FOUND, "SEARCH4002", "해당되는 사용자의 검색 기록이 없습니다."),

    // 페이지 관련 에러
    PAGE_OUT_OF_BOUNDS(HttpStatus.NOT_FOUND, "PAGE4001", "존재하지 않는 페이지입니다."),

    // 알림 관련 에러
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "NOTIFICATION4001", "알림이 존재하지 않습니다."),

    //좋아요 관련 예외
    POST_LIKE_DUPLICATED(HttpStatus.CONFLICT, "LIKE4001","좋아요 요청의 중복이 발생했습니다."),
    POST_LIKE_NOT_FOUND(HttpStatus.NOT_FOUND, "LIKE4002", "좋아요가 존재하지 않습니다."),

    // For test
    TEMP_EXCEPTION(HttpStatus.BAD_REQUEST, "TEMP4001", "테스트 용도"),

    // Comment 4xxx
    COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMENT4001", "댓글을 찾을 수 없습니다."),
    COMMENT_ACCESS_DENIED(HttpStatus.FORBIDDEN, "COMMENT4003", "댓글에 대한 권한이 없습니다."),
    COMMENT_DEPTH_EXCEEDED(HttpStatus.BAD_REQUEST, "COMMENT4000", "대댓글의 대댓글은 작성할 수 없습니다."),
    COMMENT_TOO_LONG(HttpStatus.BAD_REQUEST, "COMMENT4001", "댓글은 최대 500자까지 작성할 수 있습니다."),
    COMMENT_CONTENT_EMPTY(HttpStatus.BAD_REQUEST, "COMMENT4002", "댓글 내용을 입력해주세요."),

    // 리마인더 알림 설정 관련 에러
    INVALID_REMINDER_TIME_ORDER(HttpStatus.BAD_REQUEST, "NOTIFICATIONREMINDERSETTING4001", "Unanswered 리마인더 시간은 데일리 리마인더 시간보다 늦어야 합니다."),
    INVALID_DAILY_REMINDER_TIME(HttpStatus.BAD_REQUEST, "NOTIFICATIONREMINDERSETTING4002", "DAILY 리마인더 시간은 5분 단위로 설정할 수 있습니다."),
    INVALID_UNANSWERED_REMINDER_TIME(HttpStatus.BAD_REQUEST, "NOTIFICATIONREMINDERSETTING4003", "Unanswered 리마인더 시간은 5분 단위로 설정할 수 있습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    @Override
    public ErrorReasonDTO getReason() {
        return ErrorReasonDTO.builder()
                .message(message)
                .code(code)
                .isSuccess(false)
                .build();
    }

    @Override
    public ErrorReasonDTO getReasonHttpStatus() {
        return ErrorReasonDTO.builder()
                .message(message)
                .code(code)
                .isSuccess(false)
                .httpStatus(httpStatus)
                .build()
                ;
    }
}