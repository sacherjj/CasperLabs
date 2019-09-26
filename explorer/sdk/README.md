CasperLabs SDK for JavaScript

## How to publish to npm registry

1. Login into the npm, run the command below in the terminal with the npm account.

   ```console
   npm login
   ```

2. Modify the `package.json`, to increase the version of the package.

3. Finally, to publish the package to the npm registry, run:
   ```console
   npm publish
   ```

## Local development

Instead of publishing the package whenever we modify it, we can use `npm link` to create symbolic links.

1. On the command line, navigate to the root directory of the project that wanna use it.
2. Link CasperLabs SDK to the project, run:
   ```console
   npm link path_to_CasperLabs_SDK
   ```

### Useful links

- https://docs.npmjs.com/creating-and-publishing-unscoped-public-packages
- https://docs.npmjs.com/cli/link
