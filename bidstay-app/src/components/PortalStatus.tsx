import { Pressable, StyleSheet, Text, View } from 'react-native';

export default function PortalStatus({ title, detail, retry }: {
  title: string; detail: string; retry?: () => void;
}) {
  return <View style={styles.panel}>
    <Text style={styles.title} accessibilityRole="header">{title}</Text>
    <Text style={styles.detail} selectable>{detail}</Text>
    {retry && <Pressable accessibilityRole="button" onPress={retry} style={styles.button}>
      <Text style={styles.label}>Try again</Text>
    </Pressable>}
  </View>;
}

const styles = StyleSheet.create({
  panel: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 12, padding: 24, backgroundColor: '#f5f8ff' },
  title: { fontSize: 22, fontWeight: '700', color: '#172b4d', textAlign: 'center' },
  detail: { fontSize: 16, color: '#465672', textAlign: 'center' },
  button: { marginTop: 4, borderRadius: 9, paddingHorizontal: 18, paddingVertical: 12, backgroundColor: '#154bb8' },
  label: { color: '#fff', fontWeight: '700' },
});

export const missingPortal = __DEV__
  ? 'Start BidStay on port 8080 on the same Wi-Fi, or set EXPO_PUBLIC_PORTAL_URL to your portal origin.'
  : 'This build needs a configured HTTPS portal address.';
