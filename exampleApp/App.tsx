/**
 * Sample React Native App
 * https://github.com/facebook/react-native
 *
 * @format
 */


import { StatusBar, StyleSheet, useColorScheme } from 'react-native';
import {
  SafeAreaProvider,

} from 'react-native-safe-area-context';
import CameraView from './Camera';
import CroppedCamera from './CroppedCamera';

function App() {
  const isDarkMode = useColorScheme() === 'dark';

  return (
    <SafeAreaProvider>
      <StatusBar barStyle={isDarkMode ? 'light-content' : 'dark-content'} />
      {/* <CameraView /> */}
      <CroppedCamera />
    </SafeAreaProvider>
  );
}

export default App;
