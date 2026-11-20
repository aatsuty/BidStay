import React from 'react';
import { Text } from 'react-native';
import { Tabs } from 'expo-router/js-tabs';
import { colors } from '@/lib/theme';

const icon = (glyph: string) =>
  function TabIcon({ focused }: { focused: boolean }) {
    return <Text style={{ fontSize: 20, opacity: focused ? 1 : 0.5 }}>{glyph}</Text>;
  };

export default function TabsLayout() {
  return (
    <Tabs
      screenOptions={{
        tabBarActiveTintColor: colors.primary,
        tabBarInactiveTintColor: colors.muted,
        headerTitleStyle: { color: colors.text },
        sceneStyle: { backgroundColor: colors.bg },
      }}
    >
      <Tabs.Screen name="index" options={{ title: 'Search', tabBarIcon: icon('🔎') }} />
      <Tabs.Screen name="trips" options={{ title: 'My Trips', tabBarIcon: icon('🧳') }} />
      <Tabs.Screen name="profile" options={{ title: 'Settings', tabBarIcon: icon('⚙️') }} />
    </Tabs>
  );
}
