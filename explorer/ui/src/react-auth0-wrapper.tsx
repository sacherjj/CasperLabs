import React, { useState, useEffect, useContext } from "react";
import createAuth0Client from "@auth0/auth0-spa-js";
import Auth0Client from "@auth0/auth0-spa-js/dist/typings/Auth0Client";

// https://auth0.com/docs/quickstart/spa/react

const DEFAULT_REDIRECT_CALLBACK = () =>
  window.history.replaceState({}, document.title, window.location.pathname);

interface Auth0Props {
  children: any;
  onRedirectCallback?: (state: any) => void;
  redirect_uri: string;
  domain: string;
  client_id: string;
}

export interface Auth0Provided {
  isAuthenticated: boolean;
  user: User | undefined;
  loading: boolean;
  popupOpen: boolean;
  loginWithPopup: () => Promise<void>;
  logout: () => void;
}

export const Auth0Context = React.createContext<Auth0Provided>({
  isAuthenticated: false,
  user: undefined,
  loading: false,
  popupOpen: false,
  loginWithPopup: () => Promise.reject("This is just the default value."),
  logout: () => { }
});
export const useAuth0 = () => useContext<Auth0Provided>(Auth0Context);
export const Auth0Provider = ({
  children,
  onRedirectCallback = DEFAULT_REDIRECT_CALLBACK,
  ...initOptions
}: Auth0Props) => {
  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const [user, setUser] = useState<User>();
  const [auth0Client, setAuth0] = useState<Auth0Client>();
  const [loading, setLoading] = useState(true);
  const [popupOpen, setPopupOpen] = useState(false);

  useEffect(() => {
    const initAuth0 = async () => {
      const auth0FromHook = await createAuth0Client(initOptions);
      setAuth0(auth0FromHook);

      if (window.location.search.includes("code=")) {
        const { appState } = await auth0FromHook.handleRedirectCallback();
        onRedirectCallback(appState);
      }

      const isAuthenticated = await auth0FromHook.isAuthenticated();

      setIsAuthenticated(isAuthenticated);

      if (isAuthenticated) {
        const user = await auth0FromHook.getUser();
        setUser(user);
      }

      setLoading(false);
    };
    initAuth0();
    // eslint-disable-next-line
  }, []);

  const loginWithPopup = async (params = {}) => {
    setPopupOpen(true);
    try {
      await auth0Client!.loginWithPopup(params);
      const user = await auth0Client!.getUser();
      setUser(user);
      setIsAuthenticated(true);
    } catch (error) {
      console.error(error);
    } finally {
      setPopupOpen(false);
    }
  };

  // const handleRedirectCallback = async () => {
  //   setLoading(true);
  //   await auth0Client!.handleRedirectCallback();
  //   const user = await auth0Client!.getUser();
  //   setLoading(false);
  //   setIsAuthenticated(true);
  //   setUser(user);
  // };

  let provided: Auth0Provided = {
    isAuthenticated,
    user,
    loading,
    popupOpen,
    loginWithPopup,
    logout: () => auth0Client!.logout({ returnTo: window.location.origin })
  }

  return (
    <Auth0Context.Provider value={provided} >
      {children}
    </Auth0Context.Provider>
  );
};
