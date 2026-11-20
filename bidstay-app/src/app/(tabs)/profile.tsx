import React, { useState } from 'react';
import { ScrollView, Text } from 'react-native';
import { Button, Card, Field } from '@/components/ui';
import { useSession } from '@/lib/session';
import { colors } from '@/lib/theme';

/** DEV settings. Disappears once real login (OTP + JWT) exists. */
export default function SettingsScreen() {
  const s = useSession();
  const [url, setUrl] = useState(s.baseUrl);
  const [userId, setUserId] = useState(String(s.userId));
  const [saved, setSaved] = useState(false);

  function save() {
    const u = Number(userId);
    s.update({
      baseUrl: url.trim(),
      userId: Number.isInteger(u) && u > 0 ? u : s.userId,
    });
    setSaved(true);
    setTimeout(() => setSaved(false), 2000);
  }

  return (
    <ScrollView contentContainerStyle={{ padding: 16 }} keyboardShouldPersistTaps="handled">
      <Card style={{ backgroundColor: colors.warnSoft, borderColor: colors.warnSoft }}>
        <Text style={{ color: colors.warn, fontWeight: '700' }}>Developer settings</Text>
        <Text style={{ color: colors.warn, marginTop: 4 }}>
          Login isn&apos;t built yet. Hotels and admins have their own web portals. Seed guests: user 1 and 2.
        </Text>
      </Card>

      <Card>
        <Field label="Backend URL" value={url} onChangeText={setUrl} autoCapitalize="none" autoCorrect={false} keyboardType="url" />
        <Text style={{ color: colors.muted, marginTop: -4, marginBottom: 12, fontSize: 12 }}>
          Android emulator: http://10.0.2.2:8080 · iOS simulator / web: http://localhost:8080 · real phone: http://YOUR-COMPUTER-IP:8080
        </Text>
        <Field label="Guest user ID (X-User-Id)" value={userId} onChangeText={setUserId} keyboardType="number-pad" />
        <Button title={saved ? 'Saved ✓' : 'Save'} onPress={save} />
        <Button title="Reset to defaults" variant="ghost" onPress={() => { s.reset(); setUrl(''); }} style={{ marginTop: 8 }} />
      </Card>
    </ScrollView>
  );
}
