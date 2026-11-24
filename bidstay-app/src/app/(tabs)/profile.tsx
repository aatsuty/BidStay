import React, { useState } from 'react';
import { router, useLocalSearchParams } from 'expo-router';
import { ScrollView, Text } from 'react-native';
import { Button, Card, ErrorText, Field } from '@/components/ui';
import { errorMessage, useApi } from '@/lib/api';
import { type GuestSession, useSession } from '@/lib/session';
import { colors } from '@/lib/theme';

export default function ProfileScreen() {
  const { from } = useLocalSearchParams<{ from?: string }>();
  const session = useSession();
  const api = useApi();
  const [phone, setPhone] = useState('');
  const [name, setName] = useState('');
  const [code, setCode] = useState('');
  const [codeSent, setCodeSent] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [url, setUrl] = useState(session.baseUrl);
  const [userId, setUserId] = useState(String(session.userId));
  const [saved, setSaved] = useState(false);

  async function sendCode() {
    setBusy(true);
    setError('');
    try {
      await api.post('/api/v1/auth/guest/code', { phone });
      setCodeSent(true);
    } catch (failure) {
      setError(errorMessage(failure));
    } finally {
      setBusy(false);
    }
  }

  async function verifyCode() {
    setBusy(true);
    setError('');
    try {
      const auth = await api.post<GuestSession>('/api/v1/auth/guest/verify', { phone, code, name: name.trim() });
      session.update({ auth });
      setCode('');
      setCodeSent(false);
      if (from === 'bid' && router.canGoBack()) router.back();
    } catch (failure) {
      setError(errorMessage(failure));
    } finally {
      setBusy(false);
    }
  }

  async function signOut() {
    setBusy(true);
    setError('');
    try {
      await api.post('/api/v1/auth/logout');
      session.update({ auth: undefined });
    } catch (failure) {
      setError(errorMessage(failure));
    } finally {
      setBusy(false);
    }
  }

  function saveDemoSettings() {
    const id = Number(userId);
    session.update({
      baseUrl: url.trim(),
      userId: Number.isInteger(id) && id > 0 ? id : session.userId,
    });
    setSaved(true);
  }

  return (
    <ScrollView contentContainerStyle={{ padding: 16 }} keyboardShouldPersistTaps="handled">
      <Card>
        <Text style={{ color: colors.text, fontSize: 22, fontWeight: '700', marginBottom: 12 }}>
          {session.auth ? 'Your account' : 'Sign in or create an account'}
        </Text>
        {session.auth ? (
          <>
            {session.auth.guest.name && <Text style={{ color: colors.text }}>{session.auth.guest.name}</Text>}
            <Text style={{ color: colors.muted, marginBottom: 12 }}>+91 {session.auth.guest.phone}</Text>
            <Button title="Sign out" variant="ghost" disabled={busy} onPress={() => { void signOut(); }} />
          </>
        ) : codeSent ? (
          <>
            <Text style={{ color: colors.muted, marginBottom: 12 }}>Enter the code sent to +91 {phone}.</Text>
            <Field label="Verification code" value={code} onChangeText={setCode} keyboardType="number-pad" maxLength={6} />
            <Button title={busy ? 'Verifying…' : 'Verify and sign in'} disabled={busy || !/^\d{6}$/.test(code)} onPress={() => { void verifyCode(); }} />
            <Button title="Resend code" variant="ghost" disabled={busy} onPress={() => { void sendCode(); }} />
            <Button title="Change phone number" variant="ghost" disabled={busy} onPress={() => { setCodeSent(false); setCode(''); setError(''); }} />
          </>
        ) : (
          <>
            <Field label="Mobile number (+91)" value={phone} onChangeText={setPhone} keyboardType="phone-pad" maxLength={10} />
            <Field label="Name (optional for a new account)" value={name} onChangeText={setName} maxLength={100} />
            <Button title={busy ? 'Sending…' : 'Send verification code'} disabled={busy || !/^[6-9]\d{9}$/.test(phone)} onPress={() => { void sendCode(); }} />
          </>
        )}
        {!!error && <ErrorText message={error} />}
      </Card>

      {__DEV__ && !session.auth && (
        <Card>
          <Text style={{ color: colors.text, fontWeight: '700', marginBottom: 12 }}>Local demo settings</Text>
          <Field label="Backend URL" value={url} onChangeText={(value) => { setUrl(value); setSaved(false); }} autoCapitalize="none" autoCorrect={false} keyboardType="url" />
          <Field label="Demo guest user ID" value={userId} onChangeText={(value) => { setUserId(value); setSaved(false); }} keyboardType="number-pad" />
          <Button title={saved ? 'Saved' : 'Save demo settings'} disabled={!url.trim()} onPress={saveDemoSettings} />
        </Card>
      )}
    </ScrollView>
  );
}
