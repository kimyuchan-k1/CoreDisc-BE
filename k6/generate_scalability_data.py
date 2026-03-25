#!/usr/bin/env python3
"""
Part B: 피드 확장성 테스트를 위한 보충 시드 데이터 생성

기존 시드 데이터(generate_seed_data_v2.py)에 추가로,
팔로잉 수가 정확히 제어된 테스트 그룹을 생성한다.

사용법:
  python3 k6/generate_scalability_data.py
  mysql -u root -p coredisc < /tmp/coredisc-seed-v2/scalability_setup.sql

구조:
  Group A: member_id 4501~4515 → 팔로잉 정확히 50명
  Group B: member_id 4516~4530 → 팔로잉 정확히 200명
  Group C: member_id 4531~4545 → 팔로잉 정확히 500명
  Group D: member_id 4546~4560 → 팔로잉 정확히 1000명
  Group E: member_id 4561~4575 → 팔로잉 정확히 2000명
  Group F: member_id 4576~4590 → 팔로잉 정확히 5000명

주의: 기존 시드 데이터가 로드된 상태에서 실행해야 함
"""

import json
import os
import random
from datetime import datetime

OUTPUT_DIR = "/tmp/coredisc-seed-v2"

# 테스트 그룹 정의
GROUPS = {
    "group_50": {
        "following_count": 50,
        "member_ids": list(range(4501, 4516)),  # 15명
    },
    "group_200": {
        "following_count": 200,
        "member_ids": list(range(4516, 4531)),  # 15명
    },
    "group_500": {
        "following_count": 500,
        "member_ids": list(range(4531, 4546)),  # 15명
    },
    "group_1000": {
        "following_count": 1000,
        "member_ids": list(range(4546, 4561)),  # 15명
    },
    "group_2000": {
        "following_count": 2000,
        "member_ids": list(range(4561, 4576)),  # 15명
    },
    "group_5000": {
        "following_count": 5000,
        "member_ids": list(range(4576, 4591)),  # 15명
    },
}

# 팔로잉 대상 풀: 글이 많은 유저 우선
# T1: 1~10 (인플루언서, 글 500~800개)
# T2: 11~510 (활성 유저, 글 100~200개)
# T3: 511~5010 (일반 유저, 글 10~30개)
# T4: 5011~9010 (저활동 유저, 글 1~5개)
FOLLOW_TARGET_POOL_TIER1 = list(range(1, 11))        # 10명
FOLLOW_TARGET_POOL_TIER2 = list(range(11, 511))       # 500명
FOLLOW_TARGET_POOL_TIER3 = list(range(511, 5011))     # 4500명
FOLLOW_TARGET_POOL_TIER4 = list(range(5011, 9011))    # 4000명 (5000 그룹용)


def generate_follow_targets(count, exclude_ids):
    """
    팔로잉 대상을 선택한다.
    T1 유저를 항상 포함 (피드에 글이 많이 보이도록)
    나머지는 T2 → T3 → T4 순서로 채움 (글 많은 유저 우선)
    """
    targets = set()

    # T1 유저는 항상 포함 (10명)
    targets.update(FOLLOW_TARGET_POOL_TIER1)

    # 나머지를 T2 → T3 → T4 순서로 채움
    remaining = count - len(targets)
    if remaining > 0:
        # 글 많은 유저 우선: T2 전체 → T3 → T4
        pool = [uid for uid in FOLLOW_TARGET_POOL_TIER2 + FOLLOW_TARGET_POOL_TIER3 + FOLLOW_TARGET_POOL_TIER4
                if uid not in exclude_ids and uid not in targets]
        random.shuffle(pool)
        targets.update(pool[:remaining])

    # 정확한 수를 맞추기 위해 초과분 제거
    targets = set(list(targets)[:count])
    return targets


def generate_sql():
    """보충 SQL 생성"""
    lines = []
    lines.append("-- CoreDisc 확장성 테스트 보충 데이터")
    lines.append(f"-- Generated at: {datetime.now().isoformat()}")
    lines.append("-- 기존 시드 데이터 로드 후 실행할 것")
    lines.append("")

    all_test_member_ids = set()
    for group_data in GROUPS.values():
        all_test_member_ids.update(group_data["member_ids"])

    # Step 1: 테스트 그룹 유저의 기존 follow 관계 삭제 (팔로잉 방향만)
    lines.append("-- Step 1: 테스트 유저의 기존 팔로잉 관계 삭제 (follower = 테스트 유저)")
    test_ids_str = ",".join(str(uid) for uid in sorted(all_test_member_ids))
    lines.append(f"DELETE FROM `follow` WHERE follower_id IN ({test_ids_str});")
    lines.append("")

    # Step 2: 각 그룹별 정확한 수의 팔로잉 관계 생성
    total_follows = 0
    for group_name, group_data in GROUPS.items():
        following_count = group_data["following_count"]
        member_ids = group_data["member_ids"]

        lines.append(f"-- Step 2: {group_name} (팔로잉 {following_count}명, 유저 {len(member_ids)}명)")

        for member_id in member_ids:
            targets = generate_follow_targets(following_count, {member_id})

            # 배치 INSERT (1000개씩 분할)
            target_list = sorted(targets)
            for chunk_start in range(0, len(target_list), 500):
                chunk = target_list[chunk_start:chunk_start + 500]
                values = []
                for target_id in chunk:
                    # is_circle: T1 대상은 circle로 설정 (피드 CORE 테스트용)
                    is_circle = 1 if target_id <= 10 else 0
                    values.append(
                        f"({member_id}, {target_id}, {is_circle}, NOW(), NOW())"
                    )

                if values:
                    lines.append(
                        f"INSERT IGNORE INTO `follow` (follower_id, following_id, is_circle, created_at, updated_at) VALUES"
                    )
                    lines.append(",\n".join(values) + ";")

                total_follows += len(chunk)

        lines.append("")

    lines.append(f"-- Total: {total_follows} follow relationships created")
    lines.append("")

    # Note: member 테이블에 following_count 컬럼이 없으므로 카운트 보정 불필요
    # follow 테이블의 관계 데이터만으로 피드 쿼리가 동작함

    return "\n".join(lines)


def generate_manifest():
    """확장성 테스트용 매니페스트 생성"""
    manifest = {
        "generated_at": datetime.now().isoformat(),
        "description": "Scalability test groups with controlled following counts",
        "groups": {},
    }

    for group_name, group_data in GROUPS.items():
        manifest["groups"][group_name] = {
            "following_count": group_data["following_count"],
            "member_ids": group_data["member_ids"],
            "count": len(group_data["member_ids"]),
        }

    return manifest


def main():
    os.makedirs(OUTPUT_DIR, exist_ok=True)

    # SQL 생성
    sql = generate_sql()
    sql_path = os.path.join(OUTPUT_DIR, "scalability_setup.sql")
    with open(sql_path, "w") as f:
        f.write(sql)
    print(f"SQL generated: {sql_path}")

    # 매니페스트 생성
    manifest = generate_manifest()
    manifest_path = os.path.join(OUTPUT_DIR, "scalability-manifest.json")
    with open(manifest_path, "w") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
    print(f"Manifest generated: {manifest_path}")

    # 요약
    print("\n=== 확장성 테스트 그룹 ===")
    for group_name, group_data in GROUPS.items():
        print(f"  {group_name}: member_id {group_data['member_ids'][0]}~{group_data['member_ids'][-1]}, "
              f"following={group_data['following_count']}")

    print(f"\n사용법:")
    print(f"  1. mysql -u root -p coredisc < {sql_path}")
    print(f"  2. k6 run --env TEST_MODE=feed-by-following k6/scalability-test.js")


if __name__ == "__main__":
    main()
