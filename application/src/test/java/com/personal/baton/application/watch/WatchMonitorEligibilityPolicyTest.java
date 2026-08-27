package com.personal.baton.application.watch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("policy")
class WatchMonitorEligibilityPolicyTest {

    private final WatchMonitorEligibilityPolicy policy = new WatchMonitorEligibilityPolicy();

    @DisplayName("공개 hostname과 기본 포트를 사용하는 http 또는 https 자료만 감시 대상으로 삼는다")
    @Test
    void acceptsStaticWatchTargetPolicyIntersection() {
        assertThat(policy.isEligible("https://docs.example.com/guide"))
                .isTrue();
        assertThat(policy.isEligible("http://docs.example.com:80/guide"))
                .isTrue();
        assertThat(policy.isEligible("https://docs.example.com:443/guide"))
                .isTrue();
    }

    @DisplayName("인증 정보가 저장될 수 있는 query URL과 fragment URL은 감시하지 않는다")
    @Test
    void rejectsCredentialBearingOrFragmentUrls() {
        assertThat(policy.isEligible("https://docs.example.com/guide?token=secret"))
                .isFalse();
        assertThat(policy.isEligible("https://docs.example.com/guide#chapter"))
                .isFalse();
        assertThat(policy.isEligible("https://user:password@docs.example.com/guide"))
                .isFalse();
    }

    @DisplayName("IP literal과 모호한 숫자 주소 및 비기본 포트는 감시하지 않는다")
    @Test
    void rejectsIpLiteralsAndAmbiguousAddresses() {
        assertThat(policy.isEligible("http://127.0.0.1/"))
                .isFalse();
        assertThat(policy.isEligible("http://0x7f.0.0.1/"))
                .isFalse();
        assertThat(policy.isEligible("http://2130706433/"))
                .isFalse();
        assertThat(policy.isEligible("http://[::1]/"))
                .isFalse();
        assertThat(policy.isEligible("https://docs.example.com:8443/guide"))
                .isFalse();
    }

    @DisplayName("BATON에 저장할 수 있는 국제화 hostname은 WATCH ASCII 감시 대상에서 제외한다")
    @Test
    void excludesInternationalizedHostnameFromAsciiWatchTargets() {
        assertThat(policy.isEligible("https://한글.kr/스터디/운영-가이드"))
                .isFalse();
        assertThat(policy.isEligible("https://xn--bj0bj06e.kr/guide"))
                .isTrue();
    }
}
