import * as Clipboard from 'expo-clipboard';
import { Directory, File, Paths } from 'expo-file-system';
import * as Location from 'expo-location';
import * as Print from 'expo-print';
import * as Sharing from 'expo-sharing';

const mimeByExtension: Record<string, string> = {
  pdf: 'application/pdf',
  png: 'image/png',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
};

function escapeHtml(text: string) {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

export async function handleDeviceAction(message: Record<string, unknown>) {
  switch (message.action) {
    case 'copy': {
      if (typeof message.text !== 'string' || message.text.length > 2048) throw new Error('Invalid clipboard text');
      if (!await Clipboard.setStringAsync(message.text)) throw new Error('Clipboard unavailable');
      return null;
    }
    case 'location': {
      const permission = await Location.requestForegroundPermissionsAsync();
      if (!permission.granted) throw new Error('Location unavailable');
      const position = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced });
      return { latitude: position.coords.latitude.toFixed(2), longitude: position.coords.longitude.toFixed(2) };
    }
    case 'print': {
      if (typeof message.text !== 'string' || message.text.length > 16_384) throw new Error('Invalid receipt');
      if (message.html !== undefined) {
        if (typeof message.html !== 'string' || message.html.length > 65_536
            || !message.html.startsWith('<!doctype html>') || !message.html.includes('<head>')) {
          throw new Error('Invalid booking ticket');
        }
        const csp = `<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'">`;
        await Print.printAsync({ html: message.html.replace('<head>', '<head>' + csp) });
        return null;
      }
      await Print.printAsync({
        html: `<!doctype html><html lang="en"><meta charset="UTF-8"><body><h1>BidStay</h1><div style="white-space:pre-wrap;font:16px sans-serif">${escapeHtml(message.text)}</div></body></html>`,
      });
      return null;
    }
    case 'share': {
      const { name, base64 } = message;
      if (typeof name !== 'string' || !/^[\p{L}\p{N}._ -]{1,180}\.(pdf|png|jpe?g)$/iu.test(name)
        || typeof base64 !== 'string' || base64.length > 7_000_000 || !/^[a-z\d+/]+={0,2}$/i.test(base64)) {
        throw new Error('Invalid document');
      }
      if (!await Sharing.isAvailableAsync()) throw new Error('File sharing unavailable');
      const mimeType = mimeByExtension[name.slice(name.lastIndexOf('.') + 1).toLowerCase()];
      const directory = new Directory(Paths.cache, 'bidstay-' + Date.now() + '-' + message.id);
      directory.create();
      try {
        const file = new File(directory, name);
        file.write(base64, { encoding: 'base64' });
        await Sharing.shareAsync(file.uri, { mimeType, dialogTitle: 'Save or open document' });
      } finally {
        directory.delete();
      }
      return null;
    }
    default:
      throw new Error('Unknown device action');
  }
}
