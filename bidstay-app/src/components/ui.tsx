import React from 'react';
import {
  ActivityIndicator,
  Pressable,
  StyleProp,
  StyleSheet,
  Text,
  TextInput,
  TextInputProps,
  View,
  ViewStyle,
} from 'react-native';
import { colors, radius } from '@/lib/theme';
import type { BidStatus, BookingStatus, OfferStatus } from '@/lib/types';

// ---------------------------------------------------------------- Button
type Variant = 'primary' | 'secondary' | 'danger' | 'ghost';

export function Button({
  title,
  onPress,
  variant = 'primary',
  loading,
  disabled,
  style,
}: {
  title: string;
  onPress: () => void;
  variant?: Variant;
  loading?: boolean;
  disabled?: boolean;
  style?: StyleProp<ViewStyle>;
}) {
  const off = disabled || loading;
  const v = buttonStyles[variant];
  return (
    <Pressable
      accessibilityRole="button"
      onPress={onPress}
      disabled={off}
      style={({ pressed }) => [styles.btn, v.box, off && { opacity: 0.5 }, pressed && { opacity: 0.8 }, style]}
    >
      {loading ? <ActivityIndicator color={v.text.color} /> : <Text style={[styles.btnText, v.text]}>{title}</Text>}
    </Pressable>
  );
}

const buttonStyles = {
  primary: { box: { backgroundColor: colors.primary }, text: { color: '#fff' } },
  secondary: { box: { backgroundColor: colors.primarySoft }, text: { color: colors.primaryDark } },
  danger: { box: { backgroundColor: colors.dangerSoft }, text: { color: colors.danger } },
  ghost: { box: { backgroundColor: 'transparent', borderWidth: 1, borderColor: colors.border }, text: { color: colors.text } },
};

// ---------------------------------------------------------------- Card
export function Card({ children, style }: { children: React.ReactNode; style?: StyleProp<ViewStyle> }) {
  return <View style={[styles.card, style]}>{children}</View>;
}

// ---------------------------------------------------------------- Chip
export function Chip({ label, selected, onPress }: { label: string; selected?: boolean; onPress: () => void }) {
  return (
    <Pressable onPress={onPress} style={[styles.chip, selected && styles.chipOn]}>
      <Text style={[styles.chipText, selected && { color: '#fff' }]}>{label}</Text>
    </Pressable>
  );
}

// ---------------------------------------------------------------- Field
export function Field({ label, error, ...props }: TextInputProps & { label: string; error?: string }) {
  return (
    <View style={{ marginBottom: 12 }}>
      <Text style={styles.label}>{label}</Text>
      <TextInput
        placeholderTextColor={colors.muted}
        {...props}
        style={[styles.input, error ? { borderColor: colors.danger } : null, props.style]}
      />
      {error ? <Text style={styles.error}>{error}</Text> : null}
    </View>
  );
}

// ---------------------------------------------------------------- Stepper
export function Stepper({
  label,
  value,
  onDec,
  onInc,
  decDisabled,
  incDisabled,
}: {
  label: string;
  value: string;
  onDec: () => void;
  onInc: () => void;
  decDisabled?: boolean;
  incDisabled?: boolean;
}) {
  return (
    <View style={styles.stepperRow}>
      <Text style={styles.stepperLabel}>{label}</Text>
      <View style={styles.stepperCtl}>
        <Pressable onPress={onDec} disabled={decDisabled} style={[styles.stepBtn, decDisabled && { opacity: 0.35 }]}>
          <Text style={styles.stepBtnText}>−</Text>
        </Pressable>
        <Text style={styles.stepperValue}>{value}</Text>
        <Pressable onPress={onInc} disabled={incDisabled} style={[styles.stepBtn, incDisabled && { opacity: 0.35 }]}>
          <Text style={styles.stepBtnText}>+</Text>
        </Pressable>
      </View>
    </View>
  );
}

// ---------------------------------------------------------------- Badge
interface Tone {
  label: string;
  bg: string;
  fg: string;
}

const OFFER_TONES: Record<OfferStatus, Tone> = {
  PENDING: { label: 'Waiting for hotel', bg: colors.graySoft, fg: colors.muted },
  ACCEPTED: { label: 'Accepted', bg: colors.successSoft, fg: colors.success },
  COUNTERED: { label: 'Counter offer', bg: colors.warnSoft, fg: colors.warn },
  DECLINED: { label: 'Declined', bg: colors.dangerSoft, fg: colors.danger },
  EXPIRED: { label: 'No reply', bg: colors.graySoft, fg: colors.muted },
  UNAVAILABLE: { label: 'Sold out', bg: colors.graySoft, fg: colors.muted },
  CANCELLED: { label: 'Cancelled', bg: colors.graySoft, fg: colors.muted },
  SELECTED: { label: 'Selected', bg: colors.primarySoft, fg: colors.primaryDark },
};

const BID_TONES: Record<BidStatus, Tone> = {
  OPEN: { label: 'Open', bg: colors.primarySoft, fg: colors.primaryDark },
  HELD: { label: 'Awaiting payment', bg: colors.warnSoft, fg: colors.warn },
  BOOKED: { label: 'Booked', bg: colors.successSoft, fg: colors.success },
  CANCELLED: { label: 'Cancelled', bg: colors.graySoft, fg: colors.muted },
  EXPIRED: { label: 'Expired', bg: colors.graySoft, fg: colors.muted },
};

const BOOKING_TONES: Record<BookingStatus, Tone> = {
  PENDING_PAYMENT: { label: 'Awaiting payment', bg: colors.warnSoft, fg: colors.warn },
  CONFIRMED: { label: 'Confirmed', bg: colors.successSoft, fg: colors.success },
  EXPIRED: { label: 'Expired', bg: colors.graySoft, fg: colors.muted },
  CANCELLED: { label: 'Cancelled', bg: colors.graySoft, fg: colors.muted },
};

function Pill({ tone }: { tone: Tone }) {
  return (
    <View style={[styles.pill, { backgroundColor: tone.bg }]}>
      <Text style={[styles.pillText, { color: tone.fg }]}>{tone.label}</Text>
    </View>
  );
}

export const OfferBadge = ({ status }: { status: OfferStatus }) => <Pill tone={OFFER_TONES[status]} />;
export const BidBadge = ({ status }: { status: BidStatus }) => <Pill tone={BID_TONES[status]} />;
export const BookingBadge = ({ status }: { status: BookingStatus }) => <Pill tone={BOOKING_TONES[status]} />;

// ---------------------------------------------------------------- misc
export function ErrorText({ message }: { message?: string | null }) {
  if (!message) return null;
  return (
    <View style={styles.errorBox}>
      <Text style={{ color: colors.danger }}>{message}</Text>
    </View>
  );
}

export function Loading() {
  return (
    <View style={{ padding: 32, alignItems: 'center' }}>
      <ActivityIndicator color={colors.primary} />
    </View>
  );
}

export function Empty({ title, hint }: { title: string; hint?: string }) {
  return (
    <View style={{ padding: 32, alignItems: 'center' }}>
      <Text style={{ fontSize: 16, fontWeight: '600', color: colors.text }}>{title}</Text>
      {hint ? <Text style={{ color: colors.muted, marginTop: 6, textAlign: 'center' }}>{hint}</Text> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  btn: { minHeight: 48, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 16 },
  btnText: { fontSize: 16, fontWeight: '700' },
  card: {
    backgroundColor: colors.card,
    borderRadius: radius.lg,
    padding: 14,
    borderWidth: 1,
    borderColor: colors.border,
    marginBottom: 12,
  },
  chip: {
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: 999,
    backgroundColor: colors.graySoft,
    marginRight: 8,
    marginBottom: 8,
  },
  chipOn: { backgroundColor: colors.primary },
  chipText: { color: colors.text, fontWeight: '600' },
  label: { fontSize: 13, color: colors.muted, marginBottom: 6, fontWeight: '600' },
  input: {
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.md,
    paddingHorizontal: 14,
    paddingVertical: 12,
    fontSize: 16,
    backgroundColor: '#fff',
    color: colors.text,
  },
  error: { color: colors.danger, marginTop: 4, fontSize: 13 },
  errorBox: { backgroundColor: colors.dangerSoft, padding: 12, borderRadius: radius.md, marginBottom: 12 },
  stepperRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingVertical: 8 },
  stepperLabel: { fontSize: 15, color: colors.text, fontWeight: '600' },
  stepperCtl: { flexDirection: 'row', alignItems: 'center' },
  stepBtn: {
    width: 36,
    height: 36,
    borderRadius: 18,
    backgroundColor: colors.primarySoft,
    alignItems: 'center',
    justifyContent: 'center',
  },
  stepBtnText: { fontSize: 20, color: colors.primaryDark, fontWeight: '700', lineHeight: 22 },
  stepperValue: { minWidth: 96, textAlign: 'center', fontSize: 15, fontWeight: '600', color: colors.text },
  pill: { paddingHorizontal: 10, paddingVertical: 4, borderRadius: 999, alignSelf: 'flex-start' },
  pillText: { fontSize: 12, fontWeight: '700' },
});
