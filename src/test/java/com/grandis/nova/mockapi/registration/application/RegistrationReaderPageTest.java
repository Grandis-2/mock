package com.grandis.nova.mockapi.registration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grandis.nova.mockapi.registration.domain.Registration;
import com.grandis.nova.mockapi.registration.domain.RegistrationRepository;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;

/**
 * 목록 조회의 다음 커서 판정. HTTP 시험은 H2 를 다른 시험과 함께 써서 "남은 행이 딱 size 건" 을 만들 수 없으므로
 * 저장소를 바꿔 끼워 여기서 본다.
 */
class RegistrationReaderPageTest {

    private final RegistrationRepository repository = mock(RegistrationRepository.class);
    private final RegistrationReader reader = new RegistrationReader(repository);

    private static List<Registration> rows(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> Registration.cancelMarker("k" + i, Instant.EPOCH))
                .toList();
    }

    @Test
    @DisplayName("size + 1 건을 읽는다 - 처음이면 커서 대신 빈 문자열(모든 키가 그보다 크다)")
    void readsOneMore() {
        when(repository.findByExternalKeyGreaterThanOrderByExternalKeyAsc(anyString(), eq(Limit.of(3))))
                .thenReturn(rows(1));

        reader.findPage(null, 2);

        verify(repository).findByExternalKeyGreaterThanOrderByExternalKeyAsc("", Limit.of(3));
    }

    @Test
    @DisplayName("더 있으면 size 건만 주고 nextCursor 는 그 마지막 키")
    void moreRemain() {
        when(repository.findByExternalKeyGreaterThanOrderByExternalKeyAsc("k0", Limit.of(3))).thenReturn(rows(3));

        RegistrationPage page = reader.findPage("k0", 2);

        assertThat(page.items()).extracting(Registration::externalKey).containsExactly("k1", "k2");
        assertThat(page.nextCursor()).isEqualTo("k2");
    }

    /** size 건만 읽으면 여기서 다음 커서를 주고, 받는 쪽은 빈 페이지를 한 번 더 부른다. */
    @Test
    @DisplayName("남은 행이 딱 size 건이면 nextCursor 는 null")
    void exactlySizeRemain() {
        when(repository.findByExternalKeyGreaterThanOrderByExternalKeyAsc("k0", Limit.of(3))).thenReturn(rows(2));

        RegistrationPage page = reader.findPage("k0", 2);

        assertThat(page.items()).hasSize(2);
        assertThat(page.nextCursor()).isNull();
    }
}
