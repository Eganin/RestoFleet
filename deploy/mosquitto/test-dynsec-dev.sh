#!/usr/bin/env bash
# Проверка dynsec-dev.sh на живом стенде: ACL роли device (по направлениям, %u), clientId = deviceId, идемпотентность.
# Предусловия: стенд поднят (deploy/README.md), dynsec-dev.sh выполнен. Пароли читаются из out/dev-credentials.txt и не печатаются.
set -uo pipefail
cd "$(dirname "$0")/.."

failures=0
pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*"; failures=$((failures + 1)); }
die() { echo "FAIL: $*" >&2; exit 1; }

creds=out/dev-credentials.txt
[ -s "$creds" ] || die "нет $creds: сначала mosquitto/dynsec-dev.sh"
pw() { awk -v n="$1" '$1==n {print $2}' "$creds"; }
for u in operator dev-emu-36; do [ -n "$(pw "$u")" ] || die "в $creds нет пароля для $u"; done
OP="$(pw operator)"
DEV="$(pw dev-emu-36)"

# Скрипт со stdin выполняется в контейнере брокера (пароли не попадают в argv хоста)
broker() {
  {
    cat <<SH
OP='$OP'
DEV='$DEV'
O="-h localhost -p 8883 --cafile /mosquitto/certs/ca.crt -V mqttv5"
SH
    cat
  } | docker compose exec -T mosquitto sh -s 2>&1
}

# 1. Публикация в свой топик доходит до подписчика operator
out="$(broker <<'SH'
mosquitto_sub $O -u operator -P "$OP" -i op-sub -t 'rf/v1/#' -C 1 -W 5 >/tmp/t1.out 2>&1 &
sleep 1
mosquitto_pub $O -u dev-emu-36 -P "$DEV" -i dev-emu-36 -q 1 -t rf/v1/dev/dev-emu-36/telemetry -m own-ok 2>&1
wait
cat /tmp/t1.out
SH
)"
grep -q '^own-ok$' <<<"$out" && ! grep -qiE 'not authori[sz]ed' <<<"$out" \
  && pass "dev-emu-36 -> rf/v1/dev/dev-emu-36/telemetry доходит до operator" \
  || fail "публикация в свой топик не дошла: $out"

# 2-3. Чужой топик и свой, но «не в ту сторону» (cmd/req): не доходит, PUBACK с 0x87 (Not authorized)
for topic in rf/v1/dev/dev-emu-31/telemetry rf/v1/dev/dev-emu-36/cmd/req; do
  out="$(broker <<SH
mosquitto_sub \$O -u operator -P "\$OP" -i op-sub -t 'rf/v1/#' -C 1 -W 5 >/tmp/t2.out 2>&1 &
sleep 1
mosquitto_pub \$O -u dev-emu-36 -P "\$DEV" -i dev-emu-36 -q 1 -t $topic -m forbidden 2>&1
wait
echo "--- operator received:"
cat /tmp/t2.out
SH
)"
  echo "    [$topic] вывод mosquitto_pub / operator:"; sed 's/^/      /' <<<"$out"
  pub_part="${out%%--- operator received:*}"
  recv_part="${out#*--- operator received:}"
  grep -qiE 'not authori[sz]ed' <<<"$pub_part" && ! grep -q forbidden <<<"$recv_part" \
    && pass "$topic отклонён (Not authorized), operator ничего не получил" \
    || fail "$topic не отклонён как положено"
done

# 4. clientId не равный username: отказ в CONNACK
out="$(broker <<'SH'
mosquitto_pub $O -u dev-emu-36 -P "$DEV" -i other -q 1 -t rf/v1/dev/dev-emu-36/telemetry -m x 2>&1
echo "rc=$?"
SH
)"
echo "    [clientId other] вывод:"; sed 's/^/      /' <<<"$out"
grep -qiE 'not authori[sz]ed' <<<"$out" && ! grep -q 'rc=0' <<<"$out" \
  && pass "подключение dev-emu-36 с clientId other отклонено" \
  || fail "clientId other не отклонён"

# 5. Подписка: на чужой cmd/req — SUBACK 0x87 (135); на свои desired и cmd/req — разрешена
sub_code() {
  broker <<SH | grep -oE 'Subscribed \(mid: 1\): [0-9]+' | head -1
mosquitto_sub \$O -d -u dev-emu-36 -P "\$DEV" -i dev-emu-36 -q 1 -t '$1' -C 1 -W 2 2>&1
SH
}
c="$(sub_code rf/v1/dev/dev-emu-31/cmd/req)"
[ "$c" = 'Subscribed (mid: 1): 135' ] && pass "подписка на rf/v1/dev/dev-emu-31/cmd/req отклонена (SUBACK 0x87)" \
  || fail "подписка на чужой cmd/req: ${c:-нет SUBACK}"
c="$(sub_code '#')"
[ "$c" = 'Subscribed (mid: 1): 135' ] && pass "подписка device на # отклонена (SUBACK 0x87)" \
  || fail "подписка device на #: ${c:-нет SUBACK}"
c="$(sub_code rf/v1/dev/dev-emu-36/telemetry)"
[ "$c" = 'Subscribed (mid: 1): 135' ] && pass "подписка device на собственный telemetry отклонена (направление только на публикацию)" \
  || fail "подписка на собственный telemetry: ${c:-нет SUBACK}"
for t in rf/v1/dev/dev-emu-36/cmd/req rf/v1/dev/dev-emu-36/shadow/desired; do
  c="$(sub_code "$t")"
  [ "$c" = 'Subscribed (mid: 1): 1' ] && pass "подписка на $t разрешена (QoS 1)" || fail "подписка на $t: ${c:-нет SUBACK}"
done

# 6. Доставка команды устройству: operator публикует в cmd/req, device получает
out="$(broker <<'SH'
mosquitto_sub $O -u dev-emu-36 -P "$DEV" -i dev-emu-36 -q 1 -t rf/v1/dev/dev-emu-36/cmd/req -C 1 -W 5 >/tmp/t6.out 2>&1 &
sleep 1
mosquitto_pub $O -u operator -P "$OP" -i op-pub -q 1 -t rf/v1/dev/dev-emu-36/cmd/req -m cmd-ok 2>&1
wait
cat /tmp/t6.out
SH
)"
grep -q '^cmd-ok$' <<<"$out" && pass "operator -> cmd/req доходит до dev-emu-36" || fail "команда не дошла до устройства: $out"

# 7. Идемпотентность: повторный запуск, rc=0, файл учёток не изменился, пароли не печатаются
before_lines="$(wc -l <"$creds" | tr -d ' ')"
before_sum="$(shasum -a 256 "$creds" | awk '{print $1}')"
log="$(mktemp)"; trap 'rm -f "$log"' EXIT
mosquitto/dynsec-dev.sh >"$log" 2>&1; rc=$?
[ "$rc" = 0 ] && pass "повторный запуск dynsec-dev.sh: rc=0" || fail "повторный запуск: rc=$rc"
[ "$before_lines" = "$(wc -l <"$creds" | tr -d ' ')" ] && [ "$before_sum" = "$(shasum -a 256 "$creds" | awk '{print $1}')" ] \
  && pass "файл учёток не изменился ($before_lines строк)" || fail "файл учёток изменился при повторном запуске"
[ "$(awk '{print $2}' "$creds" | grep -cFf - "$log")" = 0 ] && pass "в выводе повторного запуска нет паролей" \
  || fail "в выводе повторного запуска есть пароли"
[ "$(stat -f '%Lp' "$creds" 2>/dev/null || stat -c '%a' "$creds")" = 600 ] && pass "права файла учёток 600" || fail "права файла учёток не 600"
[ "$(( $(wc -l <"$creds") ))" = 15 ] && pass "в файле учёток 15 клиентов (12 dev-emu, 2 dev-do, operator)" || fail "число строк учёток: $before_lines"
# Пароль после повторного запуска прежний: dev-emu-36 всё ещё подключается
out="$(broker <<'SH'
mosquitto_pub $O -u dev-emu-36 -P "$DEV" -i dev-emu-36 -q 1 -t rf/v1/dev/dev-emu-36/status -m still-ok 2>&1; echo "rc=$?"
SH
)"
grep -q 'rc=0' <<<"$out" && pass "пароль dev-emu-36 после повторного запуска прежний" || fail "dev-emu-36 не подключился после повторного запуска"

if [ "$failures" = 0 ]; then echo "dynsec-dev: all checks passed"; else echo "dynsec-dev: $failures check(s) FAILED"; exit 1; fi
