// @ts-check

import eslint from '@eslint/js'
import tseslint from 'typescript-eslint'
import globals from 'globals'
import pluginReact from 'eslint-plugin-react'
import { fixupPluginRules } from '@eslint/compat';
import reactHookPlugin from "eslint-plugin-react-hooks";

export default tseslint.config(eslint.configs.recommended, tseslint.configs.recommended, {
  ...pluginReact.configs.flat.recommended,
  plugins: {
    'react-hooks': fixupPluginRules(reactHookPlugin),
  },
  rules: {
    '@typescript-eslint/no-unused-expressions': 'off',
    '@typescript-eslint/no-unused-vars': 'off'
    // '@typescript-eslint/no-unused-expressions': [
    //   'warn',
    //   {
    //     allowShortCircuit: true,
    //     allowTernary: true,
    //     allowTaggedTemplates: true
    //   }
    // ],
  },
  settings: {
    react: {
      version: 'detect'
    }
  },
  languageOptions: {
    globals: globals.node
  }
})
